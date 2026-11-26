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

- `data/PdfEngine.kt`: every change to a PDF (merge, page operations, drawing onto pages) using PdfBox-Android.
- `data/Library.kt`: the document library, page rendering with the platform `PdfRenderer`, and thumbnails.
- `ui/`: Compose screens for the library, reader, page editor, page organizer, Settings and About.

## Differences from the iOS app

- Highlighting uses a marker you drag across the page, not text selection.
- Edits are drawn into the page itself. Use Undo (up to 10 steps while the document is open) to remove one.
- Pages are reordered with arrow buttons in the page organizer.
- Documents live in the app's private storage. Use Share to send a copy elsewhere.
- Scanning uses Google's document scanner, so it needs Google Play services.

## Releasing

Pushing a tag `android-X.Y.Z` (matching `versionName`), or running the Android workflow by hand with "upload" ticked, builds the signed bundle and uploads it to Google Play internal testing. Edit `whatsnew/whatsnew-en-US` first; promote the build to production in Play Console.
