package com.example.mlkitocrtest

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runs ML Kit Text Recognition v2 (Devanagari model, which also reads Latin)
 * over every page of a PDF, in a few render configurations, and writes one
 * JSONL file per configuration: one line per page with the full text, every
 * line with its box and confidence, and the recognition time.
 */
class MainActivity : ComponentActivity() {

    /** name, render scale (pixels per PDF point), number of horizontal strips. */
    private data class Config(val name: String, val scale: Float, val strips: Int)

    private val configs = listOf(
        Config("full_page_scale2", 2f, 1),   // ~1190 x 1684 px for A4
        Config("full_page_scale3", 3f, 1),   // ~1785 x 2526 px
        Config("strips4_scale3", 3f, 4),     // same render cut into 4 overlapping strips
    )

    private lateinit var log: TextView
    private lateinit var runButton: Button
    private lateinit var saveButton: Button
    private val outDir by lazy { File(getExternalFilesDir(null), "results") }

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startRun(uri)
    }

    private val saveZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) writeZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        runButton = Button(this).apply {
            text = "1. Pick PDF and run OCR"
            setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        }
        saveButton = Button(this).apply {
            text = "2. Save results (.zip)"
            isEnabled = false
            setOnClickListener { saveZip.launch("mlkit_results.zip") }
        }
        log = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(runButton)
            addView(saveButton)
            addView(ScrollView(this@MainActivity).apply { addView(log) })
        }
        setContentView(root)
        say("ML Kit text recognition test.\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }

    private fun say(msg: String) = runOnUiThread { log.append(msg + "\n") }

    private fun startRun(uri: Uri) {
        runButton.isEnabled = false
        saveButton.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            try {
                run(uri)
                say("\nDone. Tap 'Save results' and send the zip back.")
                runOnUiThread { saveButton.isEnabled = true }
            } catch (t: Throwable) {
                say("ERROR: $t")
            } finally {
                runOnUiThread {
                    runButton.isEnabled = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }.start()
    }

    /** Runs on a background thread. */
    private fun run(uri: Uri) {
        // Copy the PDF into the app's cache so PdfRenderer gets a seekable file.
        val pdf = File(cacheDir, "input.pdf")
        contentResolver.openInputStream(uri)!!.use { input -> pdf.outputStream().use { input.copyTo(it) } }

        outDir.deleteRecursively()
        outDir.mkdirs()
        val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val pageCount = renderer.pageCount
                say("PDF has $pageCount pages.")

                // Warm-up: the first call loads the model; keep it out of the timings.
                val warm = renderPage(renderer, 0, 1f)
                val t0 = SystemClock.elapsedRealtime()
                Tasks.await(recognizer.process(InputImage.fromBitmap(warm, 0)))
                val warmMs = SystemClock.elapsedRealtime() - t0
                warm.recycle()
                say("Model warm-up: $warmMs ms")

                val meta = JSONObject()
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk_int", Build.VERSION.SDK_INT)
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
                    .put("mlkit", "com.google.mlkit:text-recognition-devanagari:16.0.1")
                    .put("pages", pageCount)
                    .put("warmup_ms", warmMs)
                    .put("configs", JSONArray(configs.map {
                        JSONObject().put("name", it.name).put("scale", it.scale.toDouble()).put("strips", it.strips)
                    }))
                File(outDir, "meta.json").writeText(meta.toString(2))

                for (cfg in configs) {
                    say("\n== ${cfg.name} ==")
                    File(outDir, "${cfg.name}.jsonl").bufferedWriter().use { out ->
                        var totalMs = 0L
                        for (i in 0 until pageCount) {
                            val bmp = renderPage(renderer, i, cfg.scale)
                            val (page, ms) = recognize(recognizer, bmp, cfg.strips)
                            page.put("page", i + 1)
                                .put("width", bmp.width)
                                .put("height", bmp.height)
                                .put("ocr_ms", ms)
                            bmp.recycle()
                            out.write(page.toString())
                            out.newLine()
                            totalMs += ms
                            say("page ${i + 1}/$pageCount: ${page.getJSONArray("lines").length()} lines, $ms ms")
                        }
                        say("${cfg.name}: ${totalMs / pageCount} ms/page on average")
                    }
                }
            }
        }
        recognizer.close()
    }

    private fun renderPage(renderer: PdfRenderer, index: Int, scale: Float): Bitmap {
        renderer.openPage(index).use { page ->
            val w = (page.width * scale).toInt()
            val h = (page.height * scale).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE) // PdfRenderer leaves the background transparent
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    /** OCR a page, optionally as horizontal strips with 4% overlap. Boxes are in page pixels. */
    private fun recognize(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bmp: Bitmap,
        strips: Int,
    ): Pair<JSONObject, Long> {
        val lines = JSONArray()
        val texts = StringBuilder()
        var ms = 0L
        val stripH = bmp.height / strips
        val overlap = if (strips > 1) (bmp.height * 0.04).toInt() else 0
        for (s in 0 until strips) {
            val y0 = maxOf(0, s * stripH - overlap)
            val y1 = if (s == strips - 1) bmp.height else minOf(bmp.height, (s + 1) * stripH + overlap)
            val part = if (strips == 1) bmp else Bitmap.createBitmap(bmp, 0, y0, bmp.width, y1 - y0)
            val t0 = SystemClock.elapsedRealtime()
            val result: Text = Tasks.await(recognizer.process(InputImage.fromBitmap(part, 0)))
            ms += SystemClock.elapsedRealtime() - t0
            if (part !== bmp) part.recycle()

            texts.append(result.text).append('\n')
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: Rect()
                    box.offset(0, y0)
                    lines.put(
                        JSONObject()
                            .put("text", line.text)
                            .put("box", JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
                            .put("conf", line.confidence.toDouble())
                            .put("lang", line.recognizedLanguage)
                            .put("strip", s)
                    )
                }
            }
        }
        return JSONObject().put("text", texts.toString()).put("lines", lines) to ms
    }

    private fun writeZip(uri: Uri) {
        contentResolver.openOutputStream(uri)!!.use { os ->
            ZipOutputStream(os).use { zip ->
                outDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        say("Saved results zip.")
    }
}
