# ML Kit OCR test

A small Android app that runs Google ML Kit Text Recognition v2 (Devanagari
model, which also reads Latin) on every page of a PDF and exports the results.

## Install on a phone
1. Open **Releases** (on the right of the repo page, or `/releases/latest`) on
   the phone and download `mlkit-ocr-test.apk`.
2. Open it. Android asks you to allow installs from your browser or Files app;
   allow it once.

## Run
1. Tap **Pick PDF and run OCR** and choose the PDF. All pages run in three
   settings: full page at 2x, full page at 3x, and the 3x page cut into 4 strips.
2. When it says Done, tap **Save results (.zip)**.

The OCR model is bundled in the app, and no data leaves the phone.

## Build
Every push to `main` builds the APK with GitHub Actions and publishes it as a
release. To build locally: open in Android Studio, or run `gradle assembleDebug`.
