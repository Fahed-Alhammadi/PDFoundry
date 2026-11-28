<div align="center">

<img src="Website/assets/icon.png" width="112" alt="PDFoundry icon">

# PDFoundry

**Read, merge, edit, fill in and sign PDFs. Private, on your device, with no account.**

Apps for iPhone, iPad, Android, macOS, Windows and Linux.

[![iOS](https://github.com/Fahed-Alhammadi/PDFoundry/actions/workflows/ios.yml/badge.svg)](https://github.com/Fahed-Alhammadi/PDFoundry/actions/workflows/ios.yml)
[![Android](https://github.com/Fahed-Alhammadi/PDFoundry/actions/workflows/android.yml/badge.svg)](https://github.com/Fahed-Alhammadi/PDFoundry/actions/workflows/android.yml)
[![Desktop](https://github.com/Fahed-Alhammadi/PDFoundry/actions/workflows/desktop.yml/badge.svg)](https://github.com/Fahed-Alhammadi/PDFoundry/actions/workflows/desktop.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-E2511B.svg)](LICENSE)
![Platforms](https://img.shields.io/badge/platforms-iOS%20%7C%20Android%20%7C%20macOS%20%7C%20Windows%20%7C%20Linux-2B3042.svg)

[Website](https://fahed-alhammadi.github.io/PDFoundry/) · [Privacy policy](https://fahed-alhammadi.github.io/PDFoundry/privacy.html) · [Report a problem](https://github.com/Fahed-Alhammadi/PDFoundry/issues)

<br>

<img src="screenshots/ios/iphone-6.9/01-library.png" width="23%" alt="Document library on iPhone">
<img src="screenshots/ios/iphone-6.9/08-fill-sign.png" width="23%" alt="A form filled in and signed on iPhone">
<img src="screenshots/ios/iphone-6.9/05-pages.png" width="23%" alt="Page organizer on iPhone">
<img src="screenshots/ios/iphone-6.9/09-tools.png" width="23%" alt="The Tools tab on iPhone">

</div>

## Contents

- [Overview](#overview)
- [Status](#status)
- [Features](#features)
- [Screenshots](#screenshots)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Usage](#usage)
- [Tests](#tests)
- [Configuration](#configuration)
- [Releases](#releases)
- [Project structure](#project-structure)
- [Privacy](#privacy)
- [Security](#security)
- [Contributing](#contributing)
- [License](#license)

## Overview

PDFoundry is a small PDF app that does a few jobs well: reading, merging and splitting, marking up and redacting, filling in forms, signing, password protection, and making scanned pages searchable. It works entirely on the device. There is no account, no server, no advertising and no analytics. The phone apps make no network requests; the desktop app goes online only to check for its own updates.

The phone apps are native: Swift and SwiftUI on iPhone and iPad, Kotlin and Jetpack Compose on Android. The desktop app is built with Electron and runs on macOS, Windows and Linux. On every platform a PDF opens where you keep it, with no copy made. The desktop app works the way desktop PDF programs do and changes the file only when you choose Save. The phone apps save each change to the same file as you make it, and can put the file back as it was when you opened it.

## Status

| Platform | Version | Status |
|---|---|---|
| iPhone and iPad | 1.1.0 | Submitted to the App Store, waiting for review. Available to testers on TestFlight |
| Android | 1.1.0 | In closed testing on Google Play |
| macOS, Windows and Linux | 1.4.0 | Released. [Download](https://github.com/Fahed-Alhammadi/PDFoundry/releases/latest). The Mac app is signed and notarized |

Store links for the phone apps will be added here and on the [website](https://fahed-alhammadi.github.io/PDFoundry/) when the apps are public.

## Features

| | iPhone and iPad | Android | macOS, Windows and Linux |
|---|---|---|---|
| **Read** | Open a PDF where it is in Files, or receive one from Mail, Safari or the share sheet. Search the text. Scroll vertically or sideways | Open a PDF where it is with the file picker, or receive one from any app's share menu. Search the text. Pinch to zoom | Open a PDF where it is, or drop it onto the window. Search the text. Zoom |
| **Scan and create** | Scan paper with the camera. Build a PDF from photos | Scan paper with Google's document scanner. Build a PDF from photos | Build a PDF from images. No scanning |
| **Merge** | Select documents, drag them into order, merge | Select documents, order them with arrows, merge | Select documents, order them with arrows, merge |
| **Split** | Split a PDF into every page, every few pages, or the page ranges you type. The original is not changed | Same | Same, into a folder you choose |
| **Tools** | A Tools tab in four sections. Create: Scan Document, Create from Photos, Export as Images. Combine & split: Merge PDFs, Extract Pages, Split PDF. Protect & optimize: Password-protect, Compress, Redact. Enhance: Make Searchable, Page Numbers, Watermark | Same | A Tools page in the sidebar with the same sections and tools, except scanning: Create from Images instead of Scan Document and Create from Photos |
| **Reader** | A bar with three labelled groups: Markup, Fill & Sign and Pages, plus Undo. Page Numbers, Watermark and Make Searchable are also in the More menu | Same | Same |
| **Markup** | Select text, then highlight (four colours), underline or strike through. Shapes: rectangle, ellipse, line and arrow. Comments as sticky notes | Same | Same |
| **Marks** | Every mark is a real PDF annotation, so other PDF apps show it. Tap a mark later, even after reopening the file, to move, resize or delete it. Highlights, underlines and strikethroughs can be deleted but not moved | Same | Same, with a click |
| **Redact** | Draw boxes or select text, then tap **Apply**. Each redacted page becomes an image, so the hidden text is really gone from the file | Same | Same |
| **Export and compress** | Export pages as PNG or JPEG images, at screen or print quality. Compress makes a smaller copy; the text stays selectable | Same | Same |
| **Enhance** | Make Searchable recognises the text of scanned pages (English and Arabic, fully offline) so it can be searched and selected. Add page numbers or a text watermark | Same | Same |
| **Text and drawing** | Type anywhere. Draw with a finger or Apple Pencil | Type anywhere (Arabic shapes correctly). Draw with a finger or stylus, or with a highlighter pen | Type anywhere (Arabic shapes correctly). Draw with the mouse or a pen, or with a highlighter pen |
| **Fill & Sign** | Signature, Initials, Text, Date, ✓ and ✗. Draw a signature or initials once, reuse them, drag and resize before placing | Same (✓ and ✗ share one Mark button on narrow phones) | Same |
| **Forms** | Fill in a PDF's own form fields: text, check boxes, radio buttons, lists, and signature fields. Values stay real form fields, so other apps can still read and change them | Same | Same |
| **Pages** | Reorder by dragging. Rotate, duplicate, extract, delete, add blank pages | Reorder with arrows. Rotate, duplicate, extract, delete, add blank pages | Reorder by dragging or with arrows. Rotate, duplicate, extract, delete, add blank pages |
| **Undo** | Undo recent edits. Revert to Original puts the file back as it was when you opened it | Undo the last ten changes while the document is open. Revert to original puts the file back as it was when you opened it | Undo the last ten changes; nothing is written until you save |
| **Manage** | Rename, duplicate, share, print, delete. A file opened where it is can be removed from the list or copied into the app; the file itself is not touched. A file opened twice is listed once | Same | Save, Save As, print. A list of recent files |
| **Storage** | Files you open stay where you keep them, and changes are saved to the same file. Scans, new PDFs and received files go in the app's folder in Files | Files you open stay where you keep them, and changes are saved to the same file. Scans, new PDFs and received files are kept inside the app | Files stay where you keep them. Changes are written when you choose Save |
| **Passwords** | Protect a PDF with a password (Tools or the reader's More menu), or remove it. A protected PDF can be edited once you enter its password, and every save keeps it | Same | Same |
| **Permissions** | Camera, asked only when you tap Scan Document. Adding to your photo library, asked only when you tap Save Image in the share sheet after Export as Images | None | None. Goes online only to check for its own updates |

On every platform, marks and signatures are saved in the PDF itself as standard annotations, so they look the same in every other PDF reader. Passwords are used only while a document is open: they are never stored, and a protected file keeps its password after every edit. A file whose owner restricts changes, with a separate owner password you don't have, opens read-only.

## Screenshots

**iPad**

<p>
  <img src="screenshots/ios/ipad-13/01-library.png" width="32%" alt="Document library on iPad">
  <img src="screenshots/ios/ipad-13/07-tools.png" width="32%" alt="The Tools tab on iPad">
  <img src="screenshots/ios/ipad-13/02-fill-sign.png" width="32%" alt="A form filled in and signed on iPad">
</p>

**macOS, Windows and Linux**

<p>
  <img src="screenshots/desktop/01-library.png" width="49%" alt="Recent files on the desktop">
  <img src="screenshots/desktop/02-signed.png" width="49%" alt="A signed and highlighted document on the desktop">
  <img src="screenshots/desktop/15-form.png" width="49%" alt="A form being filled in on the desktop">
  <img src="screenshots/desktop/11-tools.png" width="49%" alt="The Tools page on the desktop">
  <img src="screenshots/desktop/13-split.png" width="49%" alt="Split PDF on the desktop">
  <img src="screenshots/desktop/14-protect.png" width="49%" alt="Password-protect on the desktop">
</p>

**Android**

<p>
  <img src="screenshots/android/phone/01-library.png" width="23%" alt="Document library on Android">
  <img src="screenshots/android/phone/02-fill-sign.png" width="23%" alt="A form filled in and signed on Android">
  <img src="screenshots/android/phone/07-tools.png" width="23%" alt="The Tools tab on Android">
  <img src="screenshots/android/phone/09-split.png" width="23%" alt="Split PDF on Android">
</p>

## Tech stack

| | iPhone and iPad | Android | macOS, Windows and Linux |
|---|---|---|---|
| Language | Swift 6, strict concurrency, main-actor default isolation | Kotlin | TypeScript |
| UI | SwiftUI with Liquid Glass | Jetpack Compose, Material 3 | Electron, plain HTML and CSS, no framework |
| PDF engine | PDFKit | [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) for editing, the platform `PdfRenderer` for display | [pdf-lib](https://github.com/cantoo-scribe/pdf-lib) for editing, [PDF.js](https://mozilla.github.io/pdf.js/) for display |
| Drawing | PencilKit | Compose canvas | Canvas |
| Scanning | VisionKit document camera | ML Kit document scanner | None |
| Text recognition (OCR) | Apple Vision | [Tesseract](https://github.com/tesseract-ocr/tesseract) | [Tesseract](https://github.com/tesseract-ocr/tesseract) (tesseract.js) |
| Minimum OS | iOS 26.0 | Android 8.0 (API 26) | A current macOS, Windows 10 or later, a current Linux desktop |
| Project | [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`project.yml`) | Gradle (Kotlin DSL) | npm, esbuild, electron-builder |
| Tests | Swift Testing | Instrumented JUnit tests | Node test runner, plus a self-test in a real window |

The iOS app has no third-party dependencies. The desktop app bundles everything it needs, so its window never uses the network.

## Getting started

```bash
git clone https://github.com/Fahed-Alhammadi/PDFoundry.git
cd PDFoundry
```

### iPhone and iPad

Requires Xcode 27 or later and XcodeGen (`brew install xcodegen`).

```bash
xcodegen generate
open PDFoundry.xcodeproj
```

Select the **PDFoundry** scheme and run. To run on a device, set your own team under *Signing & Capabilities*, or `DEVELOPMENT_TEAM` in `project.yml`.

### Android

Requires JDK 21 and the Android SDK. Open the `android/` folder in Android Studio, or build from the command line:

```bash
cd android
./gradlew :app:assembleDebug
```

The debug build installs next to a Play Store copy, under the ID `io.pdfoundry.app.debug`. See [android/README.md](android/README.md) for release signing.

### macOS, Windows and Linux

Requires Node.js 24 or later.

```bash
cd desktop
npm install
npm start
```

`npm run dist:mac`, `dist:win` or `dist:linux` builds the installers into `desktop/dist/`. See [desktop/README.md](desktop/README.md).

## Usage

| To | iPhone and iPad | Android | macOS, Windows and Linux |
|---|---|---|---|
| Add a document | **+** in the library: Open from Files, Scan Document, or Create from Photos | **+** in the library: Open PDF, Scan document, or Create from photos | **Open**, or drop a PDF on the window. Create from images is in the **…** menu |
| Merge | **Tools > Merge PDFs**, or **Select** in the library, choose documents, tap the merge button | **Tools > Merge PDFs**, or press and hold to select, choose documents, tap the merge button | **Tools > Merge PDFs**, or **Select**, choose files, **Merge**, then say where to save |
| Extract pages | **Tools > Extract Pages**, pick a document, tick pages, **Extract**. The original is not changed | Same | Same, then say where to save |
| Split | **Tools > Split PDF**, pick a document, choose every page, every N pages or custom ranges, **Split**. The parts are named "(part 1)", "(part 2)" and so on | Same | Same, then choose a folder |
| Protect with a password | **Tools > Password-protect**, or **…** then **Protect with Password** in the reader. Type the password twice. It can't be recovered if you forget it | **Tools > Password-protect**, or **⋮** then **Protect with Password** | **Tools > Password-protect**, or **…** then **Protect with Password** |
| Remove a password | Open the document with its password, then **…** then **Remove Password** | **⋮** then **Remove Password** | **…** then **Remove Password** |
| Highlight, underline, strike through | **Markup**, then the tool, then select the words | **Markup**, then the tool, then drag across the words | **Markup**, then the tool, then select the words |
| Add text | **Markup** or **Fill & Sign**, then **Text**, type, drag the text into place, tap **Place** | Same | Same |
| Draw | **Markup**, then **Draw**, draw, tap **Done** | Same | Same |
| Add a shape or comment | **Markup**, then the shape (rectangle, ellipse, line or arrow) or **Comment**, then drag or tap on the page | Same | Same |
| Move, resize or delete a mark | Tap the mark, then drag it, drag its handles, or **Delete**. Highlights, underlines and strikethroughs can only be deleted | Same | Click the mark, then the same |
| Redact | **Markup**, then **Redact**, draw boxes or select text, then **Apply**. Also **Tools > Redact** | Same | Same |
| Export as images | **Tools > Export as Images**, pick a document, choose PNG or JPEG and screen or print quality | Same | Same, then say where to save |
| Compress | **Tools > Compress**, pick a document. A smaller copy is made; the original is not changed | Same | Same, then say where to save |
| Make searchable, page numbers, watermark | **Tools**, or **…** in the reader, then **Make Searchable**, **Page Numbers** or **Watermark** | **Tools**, or **⋮** in the reader | **Tools**, or **…** in the reader |
| Fill in a form | Tap a field and type, or tap a box to tick it | Same | Click a field and type, then **Save** |
| Sign | **Fill & Sign**, then **Signature** (or **Initials**), draw or pick one, position it, tap **Place**. Tapping a signature field signs it directly. The bar stays in Fill & Sign for the next one | Same | Same |
| Date, tick or cross | **Fill & Sign**, then **Date**, **✓** or **✗**, position it, tap **Place** | Same | Same |
| Edit pages | **Pages** in the reader's bar | **Pages** in the reader's bar | **Pages** in the bar, the grid button in the toolbar, or **View > Organize Pages** |
| Remove an edit | Tap a mark, then **Delete**, or tap **Undo** | Same | Click a mark, then **Delete**, or **Undo** |
| Undo everything since opening | **…** then **Revert to Original** | **⋮** then **Revert to original** | Close without saving |
| Save | Saved automatically, to the same file | Saved automatically, to the same file | **Save** (Ctrl+S or ⌘S), or **Save As** for a new file |

## Tests

```bash
# iOS: unit tests on a simulator
xcodebuild test -project PDFoundry.xcodeproj -scheme PDFoundry \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro'

# Android: PDF engine and document list tests on a running emulator or device
cd android && ./gradlew :app:connectedDebugAndroidTest

# Desktop: unit tests, then the self-test in a real window
cd desktop && npm test && npm run selftest
```

All three suites run in GitHub Actions on every push. The desktop self-test runs on macOS, Windows and Linux.

## Configuration

The apps need no API keys, servers or environment variables to build and run.

## Releases

| Platform | Trigger | Result |
|---|---|---|
| iOS | Push a tag `ios-X.Y.Z`, or run the **iOS** workflow with "upload" ticked | Signed archive uploaded to TestFlight, internal and external testers |
| Android | Push a tag `android-X.Y.Z`, or run the **Android** workflow with "upload" ticked | Signed bundle uploaded to Google Play closed testing |
| Desktop | Push a tag `desktop-X.Y.Z` | Installers for all three systems on the GitHub release, the Mac app signed and notarized. Installed apps update themselves from it |

Release notes come from `AppStore/whatsnew/en-US.txt` and `android/whatsnew/whatsnew-en-US`. Promotion to the public stores is done by hand. Desktop release notes are the commits that touched `desktop/`.

## Project structure

```
PDFoundry/
├── project.yml                 # XcodeGen project definition (iOS)
├── PDFoundry/                  # iOS app
│   ├── App/                    # Entry point and tab layout
│   ├── Core/                   # Library store, PDF operations, signature store, brand
│   ├── Features/
│   │   ├── Library/            # Document grid, import, scan, merge
│   │   ├── Reader/             # PDF view, markup, drawing, placement
│   │   ├── Pages/              # Page organizer
│   │   ├── Sign/               # Signature pad and saved signatures
│   │   └── Settings/           # Settings and About
│   └── Resources/              # Asset catalog, privacy manifest
├── PDFoundryTests/             # iOS unit tests
├── android/                    # Android app
│   ├── app/src/main/           # Kotlin sources: data/ (PDF engine, library), ui/ (screens)
│   ├── app/src/androidTest/    # PDF engine tests
│   ├── play/                   # Google Play listing text and store images
│   └── whatsnew/               # Google Play release notes
├── desktop/                    # Desktop app (Electron)
│   ├── src/core/               # PDF engine, shared by the window and the tests
│   ├── src/main/               # Main process: file access, updates, window
│   ├── src/ui/                 # The window: recent files, reader, organizer, settings
│   └── test/                   # Unit tests
├── AppStore/                   # App Store listing text, store images, release notes
├── Tools/                      # Scripts that render the icons and store images
├── scripts/                    # CI helpers: TestFlight, desktop release and update signing
├── Website/                    # Project website (GitHub Pages)
├── screenshots/                # Screenshots by platform
└── .github/workflows/          # iOS, Android, desktop and website workflows
```

## Privacy

PDFoundry collects nothing. Documents and signatures stay on the device, text recognition runs offline, and the apps contain no analytics or advertising. The Android build removes the network permission altogether, so it cannot reach the internet. The desktop app's window is blocked from the network too; the only connection it makes is to GitHub, to check for and download its own updates. Read the [privacy policy](https://fahed-alhammadi.github.io/PDFoundry/privacy.html).

## Security

Found a vulnerability? Please report it privately, as described in the [security policy](SECURITY.md), not in a public issue.

## Contributing

Issues and pull requests are welcome.

1. Open an issue first for anything larger than a small fix, so the approach can be agreed.
2. Keep each pull request focused on one change.
3. Run the tests for the platform you touched before opening it.

## License

Released under the [MIT License](LICENSE).

© 2026 Fahed Alhammadi. All rights reserved.
