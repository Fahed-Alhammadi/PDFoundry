# Markup and Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Real annotations for every mark on all three apps, with select/move/resize/delete; Shapes, Comment and Redact in Markup; Export as Images, Compress, Make Searchable (OCR), Page Numbers and Watermark in the Tools tab, on iOS, Android and desktop.

**Architecture:** Three independent parts, one per platform, each on its own branch built by its own implementer (no shared code). Android and desktop first switch every existing mark to a PDF annotation with an appearance stream and get all existing mark tests green on it; then each platform adds selection, the new Markup tools, the page-changing tools (run as ordinary undoable edits in the reader) and the file-making tools (new files, source untouched).

**Tech Stack:** iOS: SwiftUI (iOS 26), PDFKit, Vision, Swift Testing, XcodeGen. Android: Kotlin, Compose Material 3, pdfbox-android, PdfRenderer, Tesseract (tesseract4android or equal, Apache 2), instrumented tests. Desktop: Electron 45, TypeScript, pdf.js, @cantoo/pdf-lib, tesseract.js (Apache 2, bundled), node tests + `npm run selftest`.

**Spec:** `docs/superpowers/specs/2026-11-27-markup-and-tools-design.md` (read it first; this plan does not repeat it).

## Global Constraints

- Markup bar: `Highlight▾ Underline Strikethrough Text Draw Shapes▾ Comment Redact` + Undo; narrow phones scroll or fold extras under "More▾"; every item has a text/accessibility label. Bar stays in its tool after placing.
- Tools sections and tiles (title — detail line): **Create**: Scan Document (phones), Create from Photos/Images, **Export as Images — Save pages as pictures**. **Combine & split**: Merge, Extract, Split. **Protect & optimize**: Password-protect, **Compress — Make a PDF smaller**, **Redact — Black out text for good**. **Enhance** (now shown): **Make Searchable — Find text in scanned pages**, **Page Numbers — Number every page**, **Watermark — Stamp text across pages**.
- Reader More menu gains: Make Searchable, Page Numbers, Watermark (only when the document is editable).
- Page Numbers, Watermark, Make Searchable, Redact from Tools: pick a file → open it in the reader (Files/Browse opens in place, never a temp copy) → tool sheet / Redact mode on top. Applied as one undoable edit; phones autosave, desktop marks unsaved. Read-only/restriction-only docs: today's read-only notice.
- Compress and Export as Images: never write the source, never list it (as Split/Extract).
- A protected document keeps its password through every new write (same machinery as last release).
- Exact copy:
  - Redact apply alert: title "Apply Redactions?", message "Redacting removes everything under the boxes for good. Each redacted page becomes an image, so its text can no longer be selected or searched, and its form fields and marks are pressed into it.", buttons Apply / Cancel.
  - Pending boxes on leaving: "Apply the redactions?" Apply / Discard / Cancel.
  - Compress no gain: "This file is already as small as it can be."
  - OCR nothing found: "No text was found on these pages."
  - iOS `NSPhotoLibraryAddUsageDescription`: "PDFoundry saves the pages you export to your photo library."
- Output names: `<name> (compressed).pdf`; export `<name> page 1.png` / `.jpg`, page numbers zero-padded to the width of the page count when it has more than 9 pages (`page 01`).
- Compress levels (Android, desktop): Smaller = 150 dpi, JPEG quality 0.6; Smallest = 100 dpi, 0.4. Keep a result only if ≥ 5% smaller. iOS: PDFKit options, one or two levels.
- Page numbers: formats `1`, `Page 1`, `1 of 12`, `Page 1 of 12`; positions bottom centre (default), bottom right, bottom left, top centre, top right, top left; "Start at" default 1; "Skip the first page" (when on, numbering starts on page 2 with the "Start at" value). Helvetica 10 pt, graphite `#12141C`, 36 pt (0.5 inch) from the edges of the page as displayed.
- Watermark: default "CONFIDENTIAL", quick choices CONFIDENTIAL / DRAFT / COPY; opacity default 25%; Diagonal (default, bottom-left to top-right) or Horizontal; colour graphite `#12141C` or ember `#E2511B`; centred, about 60% of the displayed page width; on top of content; Arabic shaped (platform-drawn image when the font cannot encode it).
- Shapes: Rectangle, Ellipse, Line, Arrow; colours = the four highlight colours + graphite (default graphite); stroke 2 pt; no fill.
- Export: All pages or Custom ranges (existing range parser and its messages); PNG or JPEG (quality 0.9); Screen 150 dpi or Print 300 dpi.
- Redaction page rendering: 300 dpi, lossless (PNG/Flate) or JPEG quality ≥ 0.92 (builder's choice, same on the platform everywhere); page size, rotation and index unchanged; the whole file rewritten so no old page content survives.
- OCR: pages with no extractable text only; English + Arabic; bundled data only; Cancel changes nothing.
- No network, no downloads; desktop window keeps refusing every non-`app://` request.
- New dependency only for OCR (Android, desktop). No onboarding or pre-permission screens. Do not change version numbers (release step does it). Every existing test keeps passing (iOS 77, Android 109, desktop 65 node + 62 self-test).

### Shared definitions (each platform implements its own copy)

- **PDFoundry tag**: every annotation the app creates gets the key `/PDFoundry true` in its dictionary and `/NM` = `pdfoundry-<uuid>`. `isOurs(annotation)` = has the key, or `/NM` starts with `pdfoundry-` (fallback when a library drops custom keys).
- **Mark kinds** and their annotation subtypes: highlight `Highlight`, underline `Underline`, strikethrough `StrikeOut`, drawing `Ink`, signature/initials/check/cross/text/date `Stamp` (Android, desktop; iOS keeps today's types incl. `FreeText`), rectangle `Square`, ellipse `Circle`, line `Line`, arrow `Line` with `/LE [/None /OpenArrow]`, comment `Text` with `/Contents` and `/Name /Comment`. Every one has an `/AP /N` appearance stream and print flag (4).
- **Movable** = ours and not text markup. **Deletable** = any annotation of the kinds above (old untagged ones included).
- Annotation rects are stored in PDF user space; UI works in "displayed page" coordinates (rotation + crop box applied). Each platform already has this conversion (`displayedRect`/`userRect`, overlay code); use it for every new kind.
- Page-changing tools are pure engine functions `bytes/file in → bytes/file out` so they plug into each platform's existing edit/undo path.

## Review Focus

1. **Redacted text still in the file**: an orphaned old content stream (pdf-lib keeps unreferenced objects; PdfBox incremental save), an annotation `/Contents`, a field `/V`, or the page's old XObjects. Test per platform: redact a word on page 2 of a 3-page file, then (a) text extraction of the saved file has no trace of the word, (b) every stream in the file, decompressed, has no trace of it, (c) pages 1 and 3 extract the same text as before.
2. **Marks on a 90°-rotated, cropped page** land where they were drawn after save + reload *and* when the file is rendered by another engine (desktop test renders Android-style output through pdf.js; Android renders through PdfRenderer; iOS through PDFKit). Test per mark kind: centre of the mark's pixels within 2% of the page size of the intended point.
3. **Undo after move/resize/delete** restores the exact previous rect and appearance (not a regenerated one). Test: place a signature, move it, resize it, delete it, Undo ×3 → rect equals the original, appearance stream bytes / rendered pixels equal.
4. **Protected document through Redact / Page Numbers / Watermark / OCR / Compress output**: still needs the same password and opens with it. One test per tool per platform.
5. **OCR Cancel and mixed documents**: a 3-page file where page 2 has text and pages 1, 3 are images; OCR processes 2 pages; Cancel mid-run leaves the bytes identical; after a full run an English word on page 1 and an Arabic word on page 3 are found by the app's own search.

---

## Part 1: iOS (branch `features/ios-markup-tools`)

Key files: `Features/Reader/ReaderModel.swift` (`markup`, `beginPlacing`, `commitPlacement`, `handleTap`, `deleteSelectedAnnotation`, `undo`, `UndoStep`, `save`, `revertToOriginal`, `canEdit`, `password`), `ReaderCanvas.swift` (PDFView, overlays), `ReaderView.swift` (More menu), `ModeBar.swift`, `Core/PDFOperations.swift` (`ImageStampAnnotation`, `writeInPlace`, `writeOptions`), `Core/Marks.swift`, `Core/PageRanges.swift`, `Features/Tools/*` (`ToolsView`, `PDFTool`, `ToolSection.shown`, `DocumentPicker`, `SplitView` as the sheet pattern). `project.yml` (Info.plist keys). Run `xcodegen generate` after adding files. Tests in `PDFoundryTests/`.

### Task I1: Tag, select, move, resize, delete

**Files:** Create `Core/Annotations.swift`; modify `ReaderModel.swift`, `ReaderCanvas.swift`, `PDFOperations.swift`; Test `PDFoundryTests/SelectionTests.swift`.
**Produces:** `enum Annotations { static func tag(_ a: PDFAnnotation); static func isOurs(_ a: PDFAnnotation) -> Bool; static func isMovable(_ a: PDFAnnotation) -> Bool; static func isDeletable(_ a: PDFAnnotation) -> Bool }`; `ReaderModel.selected: (PDFPage, PDFAnnotation)?`, `select(at: CGPoint)`, `moveSelected(to bounds: CGRect)` (one undo step per finished gesture), existing `deleteSelectedAnnotation()`; `UndoStep.changed([(PDFAnnotation, CGRect)])` and `.removed([(PDFPage, PDFAnnotation)])`.
- [ ] Check first: does PDFKit keep a custom dictionary key on write? (`setValue(true, forAnnotationKey: PDFAnnotationKey(rawValue: "/PDFoundry"))`, write, reload, read). Use `/NM` fallback if not. Record the answer in a test comment.
- [ ] Tests: `everyMarkKindIsTaggedAndSurvivesReload`; `moveResizeDeleteUndoRestoresExactly` (Focus 3); `textMarkupIsDeletableNotMovable`; `untaggedStampFromOldBuildCanBeDeleted` (build a stamp the old way without the tag); `selectionOnRotatedCroppedPage` (Focus 2: move a stamp on a 90° page with a crop box, reload, rendered pixel centre within 2%).
- [ ] A moved/resized image stamp keeps its image (ImageStampAnnotation after reload: rebuild from the appearance or keep the image; builder's call, the test proves it). Fail → implement → pass. Commit.

### Task I2: Shapes and Comments

**Files:** modify `ReaderModel.swift`, `ReaderCanvas.swift`, `ModeBar.swift`; Test `PDFoundryTests/ShapeCommentTests.swift`.
**Produces:** `enum ShapeKind { case rectangle, ellipse, line, arrow }`; `ReaderModel.beginShape(_ kind: ShapeKind, color: UIColor)`, `commitShape(from: CGPoint, to: CGPoint)`; `ReaderModel.addComment(at: CGPoint, text: String)`, `updateComment(_ a: PDFAnnotation, text: String)` (empty text → no annotation / delete).
- [ ] Tests: `shapesAreRealAnnotations` (subtypes Square/Circle/Line, arrow has OpenArrow end, colour and 2 pt border survive reload); `commentRoundTripIncludingArabic` ("مرحبا بالعالم" equals after reload, subtype Text); `emptyCommentIsNotCreated`. Fail → implement → pass. Commit.

### Task I3: Redaction

**Files:** Create `Core/Redaction.swift`; modify `ReaderModel.swift`, `ReaderCanvas.swift`, `ReaderView.swift`; Test `PDFoundryTests/RedactionTests.swift`.
**Produces:** `Redaction.apply(to document: PDFDocument, boxes: [Int: [CGRect]]) -> PDFDocument` (pages with boxes replaced by a 300 dpi rendering with black boxes, annotations/widgets removed, others untouched); `ReaderModel.pendingRedactions: [Int: [CGRect]]`, `addRedaction(_ rect: CGRect, page: Int)`, `applyRedactions()` (undoable, saved with the password), `discardRedactions()`; back navigation asks while pending (spec §5).
- [ ] Tests: Focus 1 (a)(b)(c) — for (b) read the written file and inflate every `FlateDecode` stream (Compression framework / zlib) and search; `redactionKeepsPassword` (Focus 4); `redactedPageKeepsSizeAndRotation`; `undoRedactionRestoresText`.
- [ ] Fail → implement → pass. Commit.

### Task I4: Page Numbers, Watermark, Compress, Export as Images

**Files:** Create `Core/PageStamps.swift`, `Core/Compress.swift`, `Core/ExportImages.swift`; modify `PDFOperations.swift`, `DocumentStore.swift`; Test `PDFoundryTests/PageToolsTests.swift`.
**Produces:** `struct PageNumberStyle { position, format, startAt: Int, skipFirst: Bool }`; `PageStamps.addNumbers(_ doc: PDFDocument, style: PageNumberStyle)`; `struct WatermarkStyle { text, opacity: CGFloat, diagonal: Bool, color: UIColor }`; `PageStamps.addWatermark(_ doc: PDFDocument, style: WatermarkStyle)` — both as locked `Watermark` annotations (fallback locked `Stamp`), tagged ours but not selectable; `Compress.run(_ url: URL, password: String?) async throws -> Data?` (nil = < 5% gain); `DocumentStore.saveCompressed(_ data: Data, named: String) -> PDFItem`; `ExportImages.run(_ doc: PDFDocument, pages: [Int], png: Bool, dpi: CGFloat, baseName: String) async throws -> [URL]` (temp folder, cleaned after sharing).
- [ ] Tests: `numbersOnRotatedPageWhereReaderSeesThem` (format "Page 1 of 3", bottom centre, rendered dark pixels in the bottom band of the displayed page); `skipFirstStartsOnPageTwo`; `watermarkArabicIsDrawn` (pixels present, not blank); `watermarkAndNumbersKeepPassword` (Focus 4); `compressSmallerKeepsTextSourceUnchanged` (generated file with a large photo: output smaller, text extractable, source md5 same) and `compressNoGainReturnsNil` (text-only file); `exportNamesAndCount` (12 pages → `X page 01.png`…`X page 12.png`, 300 dpi Letter → 2550×3300).
- [ ] Fail → implement → pass. Commit.

### Task I5: Make Searchable (OCR)

**Files:** Create `Core/OCR.swift`; Test `PDFoundryTests/OCRTests.swift`.
**Produces:** `OCR.pagesNeedingText(_ doc: PDFDocument) -> [Int]`; `OCR.run(_ doc: PDFDocument, pages: [Int], progress: @Sendable (Int, Int) -> Void) async throws -> Int` (Vision `VNRecognizeTextRequest`, `.accurate`, `recognitionLanguages` = English + Arabic where `supportedRecognitionLanguages` has it, `automaticallyDetectsLanguage = true`; each processed page rebuilt per spec §6a with invisible text (`CGContext.setTextDrawingMode(.invisible)`) sized to each observation's box; annotations/widgets moved over; returns pages with text added); cancellation via `Task.checkCancellation()` leaves `doc` untouched (work on a copy, swap at the end).
- [ ] Tests: Focus 5 (generate a 3-page file: image of "Invoice total" on page 1, real text on page 2, image of "فاتورة" on page 3; `pagesNeedingText == [0, 2]`; after run `doc.findString("Invoice")` and `findString("فاتورة")` hit; page 1 render pixel diff vs before ≤ 1%); `ocrCancelChangesNothing`; `ocrKeepsPassword` (Focus 4). If Vision on the test simulator has no Arabic, the Arabic assertion is skipped with a recorded reason (Swift Testing `.enabled(if:)`).
- [ ] Fail → implement → pass. Commit.

### Task I6: Bars, Tools tiles, sheets, More menu

**Files:** modify `ModeBar.swift`, `ReaderView.swift`, `ToolsView.swift`; create `Features/Tools/CompressView.swift`, `ExportImagesView.swift`, `PageNumbersSheet.swift`, `WatermarkSheet.swift`, `OCRSheet.swift`; `project.yml` (`NSPhotoLibraryAddUsageDescription`); Test: extend `PDFoundryTests` tool tests where logic lives in models; by hand on the iPhone 17 Pro Max and iPad Pro 13" simulators.
- [ ] Markup bar items and Shapes▾ menu; Comment sheet; Redact mode with Apply (count) and the alerts; Tools tiles per Global Constraints (Enhance shown); "open in the reader with the sheet on top" route for Page Numbers / Watermark / Make Searchable / Redact; More menu items; live first-page preview in Page Numbers and Watermark sheets; OCR progress with Cancel; Export share sheet.
- [ ] Run the whole suite (77 + new) green. Drive each tile once in the simulator; screenshot problems. Commit.

---

## Part 2: Android (branch `features/android-markup-tools`)

Key files: `data/PdfEngine.kt` (`edit`, `stamp`, `ink`, `markText`, `words`, `fields`, `setField`, `textBitmap`, `textBlockBitmap`, `protect`, `split`, `access`), `data/Marks.kt`, `data/Library.kt` (`DocumentStore`, `open`, `openForChange`, `saveCopy`), `data/PageRanges.kt`, `ui/ReaderScreen.kt` (`ReaderState`, `edit`, undo snapshots, More menu), `ui/PageEditor.kt` (placement overlay), `ui/TextSelection.kt`, `ui/ModeBar.kt`, `ui/ToolsScreen.kt`, `ui/SplitScreen.kt` (sheet pattern), `ui/DocumentPickerSheet.kt`. Build with `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. Tests: `androidTest` on an emulator (create a throwaway AVD; never touch `cloudfile`).

### Task A1: Marks as annotations

**Files:** Create `data/Annotations.kt`; modify `PdfEngine.kt` (`stamp`, `ink`, `markText` write annotations instead of page content); Test `androidTest/.../AnnotationTest.kt`; existing mark tests updated to assert annotations.
**Produces:** `object Annotations { fun tag(a: PDAnnotation); fun isOurs(a: PDAnnotation): Boolean; fun isMovable(a: PDAnnotation): Boolean }`; `PdfEngine.marks(file: File, pageIndex: Int): List<MarkInfo>` (`MarkInfo(id: String /*NM*/, kind: MarkKind, rect: RectF /*displayed fractions*/, movable: Boolean)`); `stamp/ink/markText` keep their signatures. Appearance: stamp = image XObject in `/AP /N` form; ink/markup/shapes = vector form XObjects; highlight uses `/BM /Multiply` in its ExtGState.
- [ ] First: confirm `PdfRenderer` (`RENDER_MODE_FOR_DISPLAY`) draws annotation appearances; record it in the test. If it does not, render annotations on top in the app (builder's call) — the visual tests must pass either way.
- [ ] Tests: every existing mark test passes on annotations; `everyMarkKindIsAnAnnotationAfterReload`; `marksOnRotatedCroppedPage` (Focus 2, PdfRenderer pixels); `arabicTextStampStillShaped`; `highlightStillMultiplies` (text under a highlight stays dark).
- [ ] Fail → implement → pass. Commit.

### Task A2: Select, move, resize, delete

**Files:** modify `PdfEngine.kt`, `ReaderScreen.kt`, `PageEditor.kt`; Test `AnnotationTest.kt`.
**Produces:** `PdfEngine.moveMark(file: File, pageIndex: Int, id: String, rect: RectF)` (translates/scales the existing appearance via `/Rect` + `/AP` `/Matrix` or `/BBox`, never regenerates the image), `PdfEngine.deleteMark(file: File, pageIndex: Int, id: String)`, `PdfEngine.markAt(file: File, pageIndex: Int, point: PointF): MarkInfo?`; `ReaderState.selected`, `select`, `move`, `deleteSelected` through `ReaderState.edit` (undo snapshot each).
- [ ] Tests: `moveResizeDeleteUndoRestoresExactly` (Focus 3; appearance bytes equal); `textMarkupDeletableNotMovable`; `oldUntaggedAnnotationDeletable`. Fail → implement → pass. Commit.

### Task A3: Shapes and Comments

**Files:** modify `PdfEngine.kt`, `ModeBar.kt`, `ReaderScreen.kt`, `PageEditor.kt`; Test `AnnotationTest.kt`.
**Produces:** `enum class ShapeKind { Rectangle, Ellipse, Line, Arrow }`; `PdfEngine.shape(file, pageIndex, kind: ShapeKind, from: PointF, to: PointF, argb: Int)`; `PdfEngine.comment(file, pageIndex, at: PointF, text: String)`, `updateComment(file, pageIndex, id, text)`, `commentText(file, pageIndex, id): String`.
- [ ] Tests: `shapesAreRealAnnotations`, `commentRoundTripIncludingArabic`, `emptyCommentIsNotCreated`. Fail → implement → pass. Commit.

### Task A4: Redaction

**Files:** Create `data/Redaction.kt`; modify `ReaderScreen.kt`, `PageEditor.kt`, `TextSelection.kt` (select text → boxes); Test `androidTest/.../RedactionTest.kt`.
**Produces:** `Redaction.apply(file: File, boxes: Map<Int, List<RectF>>, password: String?)` (render each page with `PdfRenderer` at 300 dpi including annotations, paint boxes black, replace the page's `/Contents`, `/Resources`, `/Annots` with one image; remove its widgets from `/AcroForm /Fields`; full save, not incremental); `ReaderState.pending`, `applyRedactions()`, `discardRedactions()`; back handler asks while pending (`BackHandler`).
- [ ] Tests: Focus 1 (a)(b)(c) — (b) walks every `COSStream` of the reloaded file, decoded, searching for the word; `redactionKeepsPassword` (Focus 4); `redactedPageKeepsSizeAndRotation`; `undoRedactionRestoresText`. Fail → implement → pass. Commit.

### Task A5: Page Numbers, Watermark, Compress, Export as Images

**Files:** Create `data/PageStamps.kt`, `data/Compress.kt`, `data/ExportImages.kt`; Test `androidTest/.../PageToolsTest.kt`.
**Produces:** `data class PageNumberStyle(...)`, `PdfEngine.addPageNumbers(file, style)`; `data class WatermarkStyle(...)`, `PdfEngine.addWatermark(file, style)` (page content, appended `q … Q` stream with ExtGState alpha; Arabic via `textBitmap`); `Compress.run(source: File, level: CompressLevel, password: String?, destination: File): Boolean` (false = < 5% gain, destination deleted); `ExportImages.run(source: File, pages: List<Int>, format: ImageFormat, dpi: Int, folder: File, baseName: String): List<File>`; Save to folder via SAF `ACTION_OPEN_DOCUMENT_TREE`.
- [ ] Tests: same list as Task I4 (numbers on rotated page, skip first, watermark Arabic, password kept, compress smaller + text kept + source unchanged, compress no gain, export names/count/size). Fail → implement → pass. Commit.

### Task A6: Make Searchable (OCR)

**Files:** Create `data/Ocr.kt`; `app/build.gradle.kts` (Tesseract dependency), `app/src/main/assets/tessdata/eng.traineddata`, `ara.traineddata` ("fast" models, licence Apache 2; note their source and licence in `android/README.md`); Test `androidTest/.../OcrTest.kt`.
**Produces:** `Ocr.pagesNeedingText(file: File, password: String?): List<Int>`; `Ocr.run(file: File, pages: List<Int>, password: String?, progress: (Int, Int) -> Unit, isCancelled: () -> Boolean): Int` (render at 300 dpi, Tesseract `eng+ara`, word boxes → invisible text (render mode 3) with a Unicode font that covers Arabic (bundle Noto Sans Arabic subset or use PdfBox `PDType0Font.load` of a bundled TTF; note licence), written on a copy, swapped in only when finished).
- [ ] Tests: Focus 5 (same fixture as I5; search via `PdfEngine.search`); `ocrCancelChangesNothing`; `ocrKeepsPassword`; report APK size before/after in the commit message.
- [ ] Fail → implement → pass. Commit.

### Task A7: Bars, Tools tiles, screens, More menu

**Files:** modify `ModeBar.kt`, `ReaderScreen.kt`, `ToolsScreen.kt`; create `ui/CompressScreen.kt`, `ui/ExportImagesScreen.kt`, `ui/PageNumbersSheet.kt`, `ui/WatermarkSheet.kt`, `ui/OcrSheet.kt`; manifest unchanged (no permissions).
- [ ] Same UI list as Task I6. Release (R8) build smoke test: open, place each mark, move one, run each tool once. All instrumented tests green. Commit.

---

## Part 3: Desktop (branch `features/desktop-markup-tools`)

Key files: `src/core/pdf.ts` (`stamp`, `markText`, `placement`, `userRect`, `displayedRect`, `fillFields`, `protect`, `access`, `split`), `src/core/rects.ts`, `src/core/ranges.ts`, `src/ui/reader.ts` (`Reader`, undo snapshots, Save/Save As, close questions), `src/ui/pdfview.ts` (pdf.js render + annotation layer), `src/ui/modebar.ts`, `src/ui/tools.ts`, `src/ui/split.ts` (sheet + folder pattern), `src/ui/demo.ts` (self-test), `src/main/main.ts` + `store.ts` (dialogs, `saveNew`, `readOnce`, recent), `scripts/selftest.mjs` (required count). Commands in `desktop/`: `npm test`, `npm run selftest`.

### Task D1: Marks as annotations

**Files:** Create `src/core/annotations.ts`; modify `pdf.ts` (`stamp`, `markText`, ink path write annotations instead of page content), `pdfview.ts` (canvas render with `annotationMode` that draws appearances); Test `test/annotations.test.ts`; update `textmark.test.ts`, `pdf.test.ts`.
**Produces:** `tag(dict: PDFDict)`, `isOurs(dict): boolean`, `marks(bytes, pageIndex): Promise<MarkInfo[]>` (`{ id, kind, rect: PageRect, movable }`); `stamp(bytes, pageIndex, png, rect, options)` and `markText(...)` keep their signatures but add annotations with `/AP`.
- [ ] Tests: `everyMarkKindIsAnAnnotationAfterReload`; `marksOnRotatedCroppedPage` (Focus 2; render through pdf.js in node or assert rects mathematically + self-test pixel check); `highlightMultiplies` (self-test pixel); existing tests green.
- [ ] Fail → implement → pass. Commit.

### Task D2: Select, move, resize, delete; Shapes; Comments

**Files:** modify `pdf.ts`, `reader.ts`, `pdfview.ts`, `modebar.ts`; Test `test/annotations.test.ts`, self-test checks.
**Produces:** `moveMark(bytes, pageIndex, id, rect: PageRect)`, `deleteMark(bytes, pageIndex, id)`, `shape(bytes, pageIndex, kind: 'rectangle' | 'ellipse' | 'line' | 'arrow', from, to, rgba)`, `comment(bytes, pageIndex, at, text)`, `updateComment(bytes, pageIndex, id, text)`; reader selection with handles; Delete/Backspace deletes, arrows nudge 1 pt (Shift 10 pt).
- [ ] Tests: Focus 3; `shapesAreRealAnnotations`; `commentRoundTripIncludingArabic`; `emptyCommentIsNotCreated`; self-test: select + drag a signature, resize it, Delete, Undo. Fail → implement → pass. Commit.

### Task D3: Redaction

**Files:** Create `src/core/redaction.ts`; modify `reader.ts`, `pdfview.ts`, `main.ts` (close-window question includes pending redactions); Test `test/redaction.test.ts`, self-test check.
**Produces:** `redact(bytes, pages: Map<number, { png: Uint8Array; }>, password?: string): Promise<Uint8Array>` (the window renders each page with pdf.js at 300 dpi including annotations and paints the boxes; the core replaces the page's content/resources/annots, removes its widgets from the AcroForm, and rebuilds the file so unreferenced objects are dropped — e.g. save, reload, copy into a fresh document keeping AcroForm and outline, or prune orphans; the test decides).
- [ ] Tests: Focus 1 (a)(b)(c) — (b) parse every indirect object of the saved file with pdf-lib, inflate streams with `zlib.inflateSync`, search; `redactionKeepsPassword`; `redactedPageKeepsSizeAndRotation`; self-test: draw box, Apply, saved file has no text on that page. Fail → implement → pass. Commit.

### Task D4: Page Numbers, Watermark, Compress, Export as Images

**Files:** Create `src/core/pagestamps.ts`, `src/core/compress.ts`, `src/ui/export.ts`, `src/ui/pagetools.ts`; modify `main.ts`/`store.ts` (folder dialog, Replace / Keep both / Cancel as Split); Test `test/pagetools.test.ts`.
**Produces:** `addPageNumbers(bytes, style: PageNumberStyle)`, `addWatermark(bytes, style: WatermarkStyle, arabicPng?: Uint8Array)` (page content, StandardFonts.Helvetica, ExtGState opacity); `compress(bytes, level: 'smaller' | 'smallest', reencode: (img) => Promise<Uint8Array | null>)` (core walks image XObjects; the window decodes/downsamples/JPEG-encodes via canvas; images with `/SMask` keep the mask; unsupported filters left alone) → `Uint8Array | null`; export renders with pdf.js in the window and writes via main.
- [ ] Tests: same list as Task I4; self-test: one check each for Page Numbers, Watermark, Compress, Export. Fail → implement → pass. Commit.

### Task D5: Make Searchable (OCR)

**Files:** Create `src/main/ocr.ts` (runs tesseract.js in the main process, `langPath` and `corePath` pointing at bundled files under `resources/ocr/`, `cacheMethod: 'none'`, no network); modify `package.json` (`tesseract.js`, electron-builder `extraResources` for `eng.traineddata`, `ara.traineddata` "fast"), `src/core/pdf.ts` (`addInvisibleText(bytes, pageIndex, words: { text, box: PageRect }[])` with an embedded Unicode font covering Arabic, `@pdf-lib/fontkit`, render mode 3); Test `test/ocr.test.ts`.
**Produces:** IPC `ocr:run(pages: { index, png }[]) → { index, words }[]` with progress events and cancel; `pagesNeedingText(bytes)` via pdf.js text content.
- [ ] Tests: Focus 5 (node test drives `ocr.ts` + `addInvisibleText` on the fixture; search with pdf.js text content); `ocrCancelChangesNothing`; `ocrKeepsPassword`; a check that `ocr.ts` never calls `fetch`/`http` (block network in the test and run once). Report the package size delta.
- [ ] Fail → implement → pass. Commit.

### Task D6: Bars, Tools tiles, More menu, self-test

**Files:** modify `modebar.ts`, `tools.ts`, `reader.ts`, `demo.ts`, `scripts/selftest.mjs` (new required count), `app.css`.
- [ ] Same UI list as Task I6 (desktop: Redact pending boxes ask on close document and close window). Self-test covers every new bar item and tile. `npm test` and `npm run selftest` green on macOS; CI green on Windows and Linux. Commit.

---

## Part 4: Integration (Claude, on `main`)

- [ ] Independent code review per branch (correctness, redaction leaks, annotation positions, password handling, offline OCR); fix findings; rerun each platform's tests.
- [ ] Merge the three branches via one PR; CI green on iOS, Android, desktop (macOS, Windows, Linux).
- [ ] Cross-engine check: a file marked on each platform opens with marks in the right place on the other two (pdf.js, PDFKit, PdfRenderer) and in Chrome/Preview where available.
- [ ] Release notes: `AppStore/whatsnew/en-US.txt`, `android/whatsnew/whatsnew-en-US`. Versions: Android `versionName` 1.1.0; `desktop/package.json` 1.4.0; iOS stays 1.0.0 (check App Store state first; if approved, ask about 1.1.0).
- [ ] Screenshots (Markup bar with shapes and a comment, a selected mark, Redact, Tools with all sections, one tool sheet) on iPhone 6.9", iPad 13", Android phone, desktop; Play art re-rendered and uploaded; README feature/usage tables, website cards, privacy page (iOS Photos add-only permission), `AppStore/listing-en-US.md` screenshot list; no signing details in public docs.
- [ ] On the user's approval: merge, tag `android-1.1.0`, `desktop-1.4.0`, run the iOS upload workflow. Clean up worktrees, throwaway simulators/AVDs; update the project `CLAUDE.md`.
