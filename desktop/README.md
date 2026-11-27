# PDFoundry for macOS, Windows and Linux

The desktop app: read, merge, edit and sign PDFs. [Download the latest release](https://github.com/Fahed-Alhammadi/PDFoundry/releases/latest). Built with Electron and TypeScript, without a UI framework.

## Build and run

Requires Node.js 24 or later.

```bash
npm install
npm start                # build and open the app
npm run typecheck
npm test                 # unit tests: the PDF engine, OCR (offline), the store and the update signature check
npm run selftest         # the app's own checks in a real window
npm run screenshots      # sample documents, then each screen saved to ../screenshots/desktop
npm run dist:mac         # installers into dist/ (also dist:win and dist:linux)
```

The self-test and the screenshots use a temporary data folder, so they never touch the documents of an installed copy.

## How it works

- **`src/core/pdf.ts`** is the PDF engine: pure functions from bytes to bytes, used by the window and by the tests. A position is a fraction of the page as displayed, from its top-left corner, and the engine converts it for the page's rotation and crop box (`geometry.ts`).
- **Marks are PDF annotations** (`annotations.ts`): highlights, drawings, signatures, text, shapes and comments each have their own appearance, so they look the same in every other PDF reader and can be selected later to move, resize or delete them. Page numbers and watermarks are drawn into the page itself (`pagestamps.ts`).
- **Redaction** (`redaction.ts`) replaces each redacted page with a picture of it with the areas painted black, removes the page's old content, marks and form fields, and drops every object nothing refers to before the file is written again.
- **Make Searchable** reads scanned pages with [Tesseract.js](https://github.com/naptha/tesseract.js) (Apache 2.0) in the main process (`src/main/ocr.ts`), using English and Arabic data from [tessdata](https://github.com/tesseract-ocr/tessdata_best) (Apache 2.0) that ships with the app in `resources/ocr`. It never downloads anything. The text is added invisibly over the words (`ocrtext.ts`).
- **`src/main/`** is the main process. It owns the files (`store.ts`), updates (`updater.ts`, `updateSignature.ts`) and the window. The window can only ask for the operations listed in `handlers` in `main.ts`, and can read or save only files the user opened through a system dialog, a drop, or "Open with".
- **`src/ui/`** is the window: recent files, reader, page organizer, signatures, settings. PDFs are displayed with PDF.js and changed with pdf-lib.
- **Typed text becomes an image** before it is stamped onto the page. The browser draws the text, so Arabic and other scripts are shaped correctly.
- **Files are opened in place.** Nothing is copied into the app. Edits are kept in memory and written back to the same file on Save; Save As writes a new file and leaves the original alone. Closing a document, or the window, with unsaved changes asks first.
- **The start screen lists recent files** with their real locations. Removing one from the list never deletes the file.
- **Undo** keeps the last ten versions of the open document in memory.
- **Protected PDFs** open with their password. With a password that allows changes they can be edited, and every save encrypts them again with it; otherwise they are read-only.

## Privacy and security

- The window is served from an internal `app://` address and every other request is refused, so nothing it shows can reach the network. A self-test check proves it.
- The window runs sandboxed, with context isolation and a strict content security policy, and cannot open other windows or navigate away.
- The only network use is the update check in the main process, against this repository's GitHub releases.

## Updates

Installed apps check GitHub releases once a day and download a newer version in the background.

- **macOS** updates are verified against the app's Developer ID signature.
- **Windows and Linux** updates are installed only if their checksum is listed in the release's `update-signatures.json`, which is signed with an Ed25519 key. The public half is in `src/main/updateSignature.ts`.

## Releasing

Set the version in `package.json`, commit, and push a tag `desktop-X.Y.Z`. The Desktop workflow tests on all three systems, builds the installers, signs and notarizes the Mac app, signs the update list, and publishes a GitHub release.

## Icons

`assets/icon.png` and `assets/icon.icns` are rendered from the app icon by `Tools/make_desktop_icon.swift`, then `sips` and `iconutil` for the `.icns`.
