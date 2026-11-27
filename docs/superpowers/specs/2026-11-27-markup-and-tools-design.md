# Markup and Tools: real annotations, shapes, comments, move/resize, redact, compress, page numbers, watermark, export as images, OCR

Date: 2026-11-27. Platforms: iOS/iPadOS, Android, desktop (Electron).

## Why

Asked what was left on the roadmap, the user chose to build all of it in one round ("Big round, take
your time"). The Tools redesign (`2026-11-26-tools-redesign-design.md`) already gave every item a slot:
Markup gets shapes, comments and redaction; Tools gets Export as images (Create), Compress (Protect &
optimize), OCR, Page numbers and Watermark (Enhance, a section that appears with its first tool).

Moving and resizing marks and comments need marks that stay separate objects in the file. iOS already
saves marks as PDF annotations; Android and desktop press them into the page content. The user chose
**A: Android and desktop switch to real annotations, like iOS**, so the three apps behave the same.

## Goals

- Every mark made in PDFoundry (highlight, underline, strikethrough, drawing, typed text, date,
  signature, initials, ✓, ✗, shapes, comments) is a real PDF annotation on all three platforms, and
  can be selected later, even after closing and reopening the file, to move, resize or delete it.
- Other PDF apps (Preview, Acrobat, Chrome) show these marks, and can select and delete them.
- Redaction really removes what is under the box.
- Five new tools: Compress, Page Numbers, Watermark, Export as Images, Make Searchable (OCR).
- Same names, places and behaviour on all three apps; everything stays on the device.

## Non-goals

- Changing marks that are already pressed into pages of older files (made by Android ≤ 1.0.5 or
  desktop ≤ 1.3.0). They stay as they are; only new marks are annotations.
- Editing the words of typed text after it is placed (delete and type again). Recolouring marks.
- Moving text markup (highlight, underline, strikethrough): it belongs to the text; it can be selected
  and deleted only.
- A list or panel of all comments; replies, authors, review status.
- Selecting or moving marks made by other apps beyond what the platform gives for free (they may be
  deletable; not required).
- Redaction search ("redact every email address"), redacting metadata/bookmarks, or keeping the text of
  a redacted page.
- OCR languages other than English and Arabic on Android and desktop (iOS: whatever Vision offers).
- Editing an existing watermark or page numbers (Undo/Revert remove them while the document is open).
- Network use of any kind (desktop's updater stays the only exception, as today).

## Anti-goals (a failure even if it works)

- A redacted word can still be found, selected, copied or extracted from the saved file by any means
  (text extraction, the old content stream, an annotation, a form field value, an incremental save
  that keeps the old page).
- After the annotation switch, a mark that worked before no longer survives save and reload, shows in
  the wrong place on rotated or cropped pages, loses Arabic shaping, or disappears in Preview/Acrobat/
  Chrome.
- A protected document loses its password after any new action (same rule as last release).
- Compress, Export or OCR changes the source file without the user asking for it; Compress makes text
  unselectable.
- OCR changes how a page looks.
- A tool downloads anything (models, language data, fonts). All data is bundled.
- Any pre-permission screen (App Review 5.1.1(iv)); any new permission other than the one in "Export
  as Images" below, asked in context.

## Approaches

The user picked **one big round** over two rounds (Markup first, then Tools). Setup as the last two
releases: one implementer agent per platform in its own git worktree, an independent code review per
platform, Claude fixes, tests on simulator / emulator / self-test, merge, screenshots, README, website,
release. Within each platform the builder does the annotation switch **first** and gets every existing
mark test passing on it before adding anything new.

Alternatives considered: keeping baked-in marks on Android/desktop (rejected by the user: no
move/resize or comments there); two release rounds (rejected by the user, accepting one large review).

## Design

### 1. Marks as annotations (Android and desktop; iOS already does this)

- Each mark is written as a PDF annotation with an appearance stream, so every viewer draws it the
  same way:
  - highlight / underline / strikethrough: `Highlight` / `Underline` / `StrikeOut` with QuadPoints;
  - drawing: `Ink`;
  - signature, initials, ✓, ✗, typed text and date: `Stamp` whose appearance is the image the app
    already makes today (so Arabic stays shaped as it is now). *(Claude's call: typed text as a stamp
    image, not FreeText, because FreeText fonts do not shape Arabic in other viewers.)*
  - shapes: `Square`, `Circle`, `Line` (arrow = `Line` with an open-arrow line ending);
  - comment: `Text` (sticky note) with `/Contents`.
- iOS keeps the annotation types it uses today (including its FreeText for typed text) and adds the
  new ones; it only needs the tag below and the select/move/resize work. The builder checks that PDFKit
  keeps the custom tag key on save; if it does not, another field identifies PDFoundry marks (for
  example a fixed `/NM` prefix).
- Marks made by earlier builds (untagged: iOS TestFlight and App Store 1.0.0 marks) *(Claude's call)*:
  they can be selected and deleted; moving/resizing them is best effort (only if their look is kept,
  e.g. a reloaded image stamp keeps its appearance), never required.
- Rotated pages and crop boxes are handled as the current overlay code does; a test per mark type
  proves position and size after save and reload on a 90°-rotated, cropped page.
- Undo and Revert keep working as today (Android: file snapshots; desktop: byte snapshots; iOS: its
  own undo). Each mark change (add, move, resize, delete) is one Undo step.
- Annotations PDFoundry creates are tagged (an app-private key on the annotation dictionary, e.g.
  `/PDFoundry true`) so the app knows which marks it can move/resize. *(Claude's call.)*

### 2. Selecting, moving, resizing, deleting marks

- With no placement tool active (reading, or a mode open with nothing picked), a tap on a mark selects
  it: a box with corner handles and a small menu (Delete; for comments also Open). Tapping elsewhere
  deselects. A placement tool, as today, wins over marks and form fields.
- Drag inside the box moves; drag a corner resizes (stamps and shapes keep proportion when resizing
  from a corner; shapes and lines can also stretch freely: builder's call on the handle). Ink drawings
  scale. Text markup can be selected and deleted only.
- Marks not made by PDFoundry: deletable if the platform makes it easy, never required to move.
- Phones save after the change ends (finger lifted), desktop marks the document changed (Save).
- Keyboard on desktop: Delete/Backspace removes the selected mark, arrows nudge it (builder's call).

### 3. Shapes (Markup)

Markup bar gains **Shapes▾**: Rectangle, Ellipse, Line, Arrow. Drag on the page to draw. Colour: the
four highlight colours plus the graphite ink, picked from the same menu (default graphite). One stroke
width (about 2 pt), no fill. *(Claude's calls.)* After drawing, the bar stays in Shapes.

### 4. Comments (Markup)

Markup bar gains **Comment**: tap the page to place a note icon and type the comment in a small sheet
or popover; Done saves, an empty comment is not created. Tapping a comment opens it to read, edit or
delete. Comments show as the standard note icon in other apps, with the text in their popup.

### 5. Redact (Markup)

- Markup bar gains **Redact**: drag boxes over the page, or select text (where text selection exists)
  to mark areas. Marked areas show as red outlines, not yet applied; they can be deleted.
- A bar button **Apply** (with the count) asks: "Redacting removes everything under the boxes for
  good. Each redacted page becomes an image, so its text can no longer be selected or searched, and
  its form fields and marks are pressed into it." Apply / Cancel.
- Apply replaces each affected page with a rendering of it (page content and all annotations drawn,
  at 300 dpi, JPEG-free so it stays crisp; builder picks lossless or high-quality format) with solid
  black boxes, and the page's annotations and form-field widgets removed. Page size, rotation and
  position in the document stay. The whole file is rewritten (no incremental update that keeps the old
  page). Other pages are untouched.
- Undo/Revert work while the document is open (they hold the old bytes in private memory/cache only).
  Pending (unapplied) boxes are held in memory only and never written to the file. *(Claude's calls:)*
  while boxes are pending, closing Redact mode (the bar's ×) or going back from the reader (back
  button and swipe-back on phones; closing the document or window on desktop) asks "Apply the
  redactions?" Apply / Discard / Cancel. If the app is sent to the background and killed, pending boxes
  are simply lost (nothing applied, nothing leaked). This question exists only while boxes are pending;
  the phones still have no general close question.
- A protected document stays protected with its password.
- Also listed in Tools › Protect & optimize as **Redact**: it opens the picked document in the reader in
  Redact mode.

### 6. How the new tools open documents

Page Numbers, Watermark and Make Searchable change a document, so (Claude's call) their Tools tile
picks a file and **opens it in the reader with the tool's sheet on top**; the tool is applied as one
ordinary edit, so Undo, Revert, autosave (phones) and Save (desktop) work exactly as for any edit, and
the password of a protected file is kept. The same three are also in the reader's More menu. Picking a
file from Files/Browse opens it in place, as `+ › Open` does (never a temporary copy). Restriction-only
or read-only documents refuse with today's read-only notice.

Compress and Export as Images make new files and never write the source (same rule as Split/Extract:
the source is not added to the library or recent list).

### 6a. How content is added on iOS *(Claude's call, after the builder check)*

PDFKit cannot add to a page's existing content stream, and iOS may not use third-party code. So:
- **Page numbers and watermark on iOS** are written as locked, non-selectable annotations of the
  standard PDF `Watermark` subtype (drawn with the same look; printed; not movable or deletable in
  PDFoundry; the app's Undo/Revert remove them). If PDFKit cannot write that subtype with an
  appearance, a locked `Stamp` with an appearance is used. Android and desktop add them to the page
  content as below. The page looks the same on all three.
- **OCR on iOS** rebuilds only the pages it recognises: the original page is drawn (vector, unchanged)
  into a new page together with the invisible text, its annotations and form widgets are moved onto the
  new page, and it replaces the old one. Known limit: outline entries and internal links that point at
  a rebuilt page may stop working (scanned pages rarely have them); the sheet does not need to say so.
- **Redaction** already replaces the page with a rendering on every platform, so no difference.

### 7. Compress (Tools › Protect & optimize)

- Pick a file → sheet: **Smaller** (images at 150 dpi, quality 0.6) or **Smallest** (100 dpi, 0.4)
  *(Claude's values)*. Shows the current size; after running, shows the new size.
- Only images are re-encoded (downsampled and JPEG); text, vectors, links, form fields and annotations
  stay. Images with transparency keep it (builder's call how: skip them or keep the soft mask).
- iOS *(Claude's call)*: uses PDFKit's own image options (`saveImagesAsJPEG`, `optimizeImagesForScreen`,
  and a quality option where available); the two levels may give the same result there, in which case
  iOS shows a single choice. The exact dpi/quality values apply to Android and desktop.
- If the result is not at least 5% smaller, it says "This file is already as small as it can be" and
  keeps nothing.
- Output `<name> (compressed).pdf`: phones into the library, desktop asks where (refuses the source).
  A protected source asks for its password and the output keeps it.

### 8. Page Numbers (Tools › Enhance, reader More)

Sheet: position (bottom centre default, bottom right, bottom left, top centre, top right, top left),
format (`1`, `Page 1`, `1 of 12`, `Page 1 of 12`), start at (default 1), and "Skip the first page".
Small graphite Helvetica, 10 pt, 0.5 inch from the edge, placed on the page as the reader sees it
(rotation and crop box respected). Added to the page content (not an annotation). A live preview of
the first page in the sheet. *(Claude's calls.)*

### 9. Watermark (Tools › Enhance, reader More)

Sheet: text (default "CONFIDENTIAL"; quick choices CONFIDENTIAL, DRAFT, COPY), opacity (default 25%),
diagonal or horizontal, colour (graphite or ember), all pages. Centred, sized to about 60% of the page
width. Added to the page content, drawn on top and semi-transparent so it also shows over images. Arabic text is shaped (drawn as an image by the platform, as stamps are). Live preview.
*(Claude's calls.)*

### 10. Export as Images (Tools › Create)

- Pick a file → sheet: pages (All / Custom ranges using the Split range parser), format PNG or JPEG,
  quality Screen (150 dpi) or Print (300 dpi). A protected file asks for its password.
- Files named `<name> page 1.png` … (zero-padded when more than 9 pages, so they sort).
- iOS: the share sheet with the images (so Save to Files and Save Image work). Saving to Photos from
  the share sheet needs `NSPhotoLibraryAddUsageDescription` ("PDFoundry saves the pages you export to
  your photo library."); the system asks only when the user taps Save Image. This is the one new
  permission in this release.
- Android: the share sheet, plus **Save to folder** (system folder picker; no permission).
- Desktop: asks for a folder; existing names ask once (Replace / Keep both / Cancel), as Split does.

### 11. Make Searchable (OCR) (Tools › Enhance, reader More)

- Recognises text on pages that have no text yet and adds it as invisible text placed over the words,
  so search, selection and copy work; the page looks exactly the same. Pages that already have text are
  skipped (the sheet says how many will be processed). Progress with Cancel; Cancel changes nothing.
- Languages: English and Arabic. iOS uses Vision (on device, the languages it supports including
  Arabic where available; automatic language detection). Android uses Tesseract (Apache 2, via a
  maintained Android build such as tesseract4android) with bundled `eng` and `ara` "fast" data.
  Desktop uses tesseract.js with its core and `eng` + `ara` data bundled in the app (loaded from the
  app's own files, never a CDN). *(Claude's calls; Android's ML Kit text recognition does not read
  Arabic.)*
- Arabic recognised text is stored in reading order so search and copy give correct Arabic.
- If nothing is found: "No text was found on these pages." and nothing changes.

### 12. Bars and Tools grid

- Markup bar: `Highlight▾ Underline Strikethrough Text Draw Shapes▾ Comment Redact` with Undo; on
  narrow phones it scrolls or folds items under a "More▾" (builder's call), every item labelled.
- Tools sections: **Create**: Scan (phones), Create from Photos/Images, **Export as Images**.
  **Combine & split**: Merge, Extract, Split. **Protect & optimize**: Password-protect, **Compress**,
  **Redact**. **Enhance** (now shown): **Make Searchable**, **Page Numbers**, **Watermark**.
- Tile symbols and colours from the brand palette (builder's call).

## Constraints

- iOS: SwiftUI, iOS 26, no third-party dependencies, XcodeGen. Android: Compose, Material 3, no new
  permissions; new dependency allowed only for OCR (Tesseract build, Apache-2 or similar). Desktop:
  TypeScript, no UI framework; new dependency allowed only for OCR (tesseract.js, Apache 2), bundled
  offline; the window's "refuse every other request" rule stays (OCR data served from `app://`).
- App size: OCR adds up to about 20 MB on Android and desktop; report the actual size.
- Existing tests keep passing (iOS 77, Android 109 instrumented, desktop 65 node + 62 self-test).
- New tests per platform at least: every mark type is an annotation after save and reload (rotated and
  cropped page), and still shows in pdf.js/PDFKit/PdfRenderer; move/resize/delete round-trip; comment
  text round-trip including Arabic; redaction: the redacted text is gone from text extraction and from
  every stream in the file, other pages untouched, protected file keeps its password; compress: output
  smaller, text still extractable, source unchanged; page numbers on a rotated page land where the
  reader sees them; watermark Arabic shaping; export count/names/size; OCR on a generated scanned page
  (English and Arabic) makes the words findable and does not change the page's rendering.
- Desktop self-test covers the new bar items, selecting/moving a mark, and each new tool, and passes on
  all three OSes.
- Public docs: no signing details (secret names, key files) in README/Website.

## Release

- iOS: a new TestFlight build of **1.0.0** while version 1.0.0 is in App Review. If Apple has approved
  1.0.0 by release time, ask the user before using 1.1.0.
- Android: **1.1.0** (new features and the annotation switch), tag `android-1.1.0`, closed testing.
  *(Claude's call; ask the user before tagging.)*
- Desktop: **1.4.0**, tag `desktop-1.4.0`.
- Release notes, screenshots (including the Play art, uploaded this time), README feature/usage tables,
  website cards, privacy page (the new Photos add-only permission on iOS) and the App Store listing
  file updated.

## Left to the builder

Exact symbols and tile colours, handle style and hit sizes, how the bars fit narrow phones, the
comment sheet layout, the redaction page image format, Tesseract binding choice, OCR progress UI,
how transparency is kept in Compress, preview rendering in the sheets, and Undo wiring per platform.
