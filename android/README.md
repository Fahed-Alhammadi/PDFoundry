# PDFoundry for Android

Native Kotlin and Jetpack Compose app with the same jobs as the iOS app: read, merge, edit and sign PDFs.

## Build

Needs JDK 21 and the Android SDK (compile SDK 37.1).

```bash
cd android
./gradlew :app:assembleDebug             # debug APK, installs as io.pdfoundry.app.debug
./gradlew :app:connectedDebugAndroidTest # engine tests, needs a running emulator or device
./gradlew :app:bundleRelease             # signed bundle for Google Play
```

Release builds are signed when `android/keystore.properties` exists (git-ignored):

```properties
storeFile=/path/to/YOUR_KEYSTORE
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

## How it works

- `data/PdfEngine.kt`: every change to a PDF (merge, page operations, marks, form fields) using PdfBox-Android.
- `data/Annotations.kt`: marks are real PDF annotations (highlight, underline, strikethrough, ink, stamps for signatures and typed text, shapes, comments), each with its own appearance stream and a PDFoundry tag, so they can be selected, moved, resized and deleted later, here or in other apps.
- `data/AnnotationLayer.kt`: the platform `PdfRenderer` draws page content only, so the marks are drawn on top from their appearance streams.
- `data/Redaction.kt`, `PageStamps.kt` (page numbers, watermark), `Compress.kt`, `ExportImages.kt`, `Ocr.kt` (Make Searchable): the Tools.
- `data/Library.kt`: the document library, page rendering with the platform `PdfRenderer`, and thumbnails.
- `data/OutsideFiles.kt`: reading and writing files that are opened where they live, through the system file picker's links.
- `ui/`: Compose screens for the library, reader, page editor, page organizer, Settings and About.

## Third-party code and data

| What | Where | Licence | Source |
| --- | --- | --- | --- |
| PdfBox-Android 2.0.27.0 | Gradle dependency | Apache 2.0 | `com.tom-roush:pdfbox-android` (Maven Central) |
| Tesseract4Android 4.9.0 (Tesseract OCR and Leptonica) | Gradle dependency | Apache 2.0 (Leptonica: BSD 2-clause) | `cz.adaptech.tesseract4android:tesseract4android` (JitPack, the project's own channel; only this group is fetched from JitPack) |
| English and Arabic OCR models (`eng.traineddata`, `ara.traineddata`, "fast" LSTM) | `app/src/main/assets/tessdata/` | Apache 2.0 | github.com/tesseract-ocr/tessdata_fast (main branch, November 2026) |
| Noto Naskh Arabic Regular (unhinted TTF), for the invisible Arabic text Make Searchable adds | `app/src/main/assets/fonts/` (licence text next to it) | SIL Open Font License 1.1 | github.com/notofonts/arabic, release NotoNaskhArabic-v2.021 |

Everything is bundled: the app downloads nothing at run time.

## Differences from the iOS app

- Marks made by Android 1.0.5 and earlier were drawn into the page itself and stay that way; marks made now are annotations that can be moved and deleted at any time. Undo (up to 10 steps while the document is open) works for every change.
- Make Searchable uses Tesseract with bundled English and Arabic models (iOS uses Apple's Vision).
- Pages are reordered with arrow buttons in the page organizer.
- A PDF opened with Open PDF stays where it is and is saved there; the app keeps a working copy of it in its private storage. Documents you scan, create or receive from another app live in the app's private storage: use Share to send a copy elsewhere.
- Scanning uses Google's document scanner, so it needs Google Play services.

## Releasing

Pushing a tag `android-X.Y.Z` (matching `versionName`), or running the Android workflow by hand with "upload" ticked, builds the signed bundle and uploads it to Google Play closed testing. Edit `whatsnew/whatsnew-en-US` first; promote the build to production in Play Console.
