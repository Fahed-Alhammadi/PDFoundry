# Tools Tab and Reader Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every planned PDFoundry feature a home: a Tools tab for whole-file tools and Markup / Fill & Sign / Pages modes in the reader, on iOS, Android and desktop, plus text-selection markup on Android and desktop.

**Architecture:** Three independent parts, one per platform, each built on its own branch by its own implementer (they share no code). Inside each app the change is UI structure: a new Tools screen with a document picker, a reader bar driven by a small "active mode" state, and an extract-only page picker. Editing logic is reused; the only new engine work is text markup on Android (PdfBox text positions) and desktop (pdf.js text layer).

**Tech Stack:** iOS: SwiftUI (iOS 26, Liquid Glass), PDFKit, Swift Testing, XcodeGen. Android: Kotlin, Jetpack Compose Material 3, pdfbox-android, instrumented tests. Desktop: Electron 45, TypeScript, pdf.js, @cantoo/pdf-lib, node tests + `npm run selftest`.

**Spec:** `docs/superpowers/specs/2026-11-26-tools-redesign-design.md` (read it first; this plan does not repeat it).

## Global Constraints

- Names, everywhere: tabs Documents (desktop rail: Files), Tools, Settings. Modes Markup, Fill & Sign, Pages. Tools Highlight, Underline, Strikethrough, Text, Draw, Signature, Undo. Tools tab: Scan Document (phones only), Create from Photos (desktop: Create from Images), Merge PDFs, Extract Pages.
- Tools tab sections: Create, Combine & split. Sections "Protect & optimize" and "Enhance" exist in code as empty and are not shown.
- Markup bar: `× Highlight▾ Underline Strikethrough Text Draw ↶`. Fill & Sign bar: `× Signature Text ↶`. Reading bar: `Markup  Fill & Sign  Pages   ↶`. Pages opens the existing organizer; it is not a mode.
- After placing / drawing / marking, the bar returns to the mode it came from. The reader always opens in the reading bar. Read-only documents show no mode buttons (existing notice stays).
- Highlight colours (iOS values, used on all platforms): Yellow `systemYellow` α0.5, Green α0.4, Pink α0.4, Blue α0.35; Underline and Strikethrough red.
- Extract Pages from Tools never changes the source file, never opens the reader, never adds the source to the library/recent list.
- No new permissions (Android manifest stays permission-free; iOS camera only via the system scanner on Scan). No onboarding or pre-permission screens.
- No third-party dependencies added on iOS; none added on Android or desktop either.
- Do not change version numbers; the release step does that (iOS stays 1.0.0, Android → 1.0.4, desktop → 1.2.0).
- Every existing test keeps passing.

## Review Focus

1. **Signing twice in a row** — after placing a signature the bar must still be in Fill & Sign so the second Signature is one tap. Test per platform (iOS: model/UI state test; Android: `ReaderModeTest`; desktop: self-test check).
2. **Text markup on a rotated or cropped page (Android, desktop)** — marks must land on the selected words, not offset. Engine tests on a page rotated 90° and one with a crop box.
3. **Text markup on a page with no text (a scan)** — nothing is applied, the hint appears, nothing crashes. Engine test returns empty geometry; UI shows the hint.
4. **Extract Pages on a file opened in place** — source bytes unchanged after extracting (compare bytes/md5 before and after). One test per platform.
5. **Largest text size on a small phone** — the reading bar's three mode labels plus Undo still fit (labels may drop to icon + accessibility label). Checked by hand in the simulator/emulator at the largest accessibility size; screenshot attached to the report.

---

## Part 1: iOS (branch `redesign/ios`)

Key files: `PDFoundry/App/PDFoundryApp.swift` (`RootView` tabs), `Features/Reader/ReaderView.swift` (toolbar at lines ~176–255), `Features/Reader/ReaderModel.swift` (`Mode`, `markup`, `beginPlacing`, `commitPlacement`, `commitDrawing`), `Features/Library/LibraryView.swift` (`+` menu, scanner, photo picker, merge sheet), `Features/Pages/PageOrganizerView.swift`. Run `xcodegen generate` after adding files.

### Task I1: Reader tool mode state

**Files:** Modify `Features/Reader/ReaderModel.swift`; Test `PDFoundryTests/ReaderToolModeTests.swift` (new).

**Interfaces — Produces:** `enum ToolMode { case none, markup, fillSign }` and `var toolMode: ToolMode` on `ReaderModel`. `cancelMode()`, `commitPlacement()`, `commitDrawing()` keep `toolMode` unchanged. `toolMode` starts `.none` on every `ReaderModel` init.

- [ ] Write tests: `toolModeStartsAtNone`; `placingASignatureKeepsFillSign` (set `.fillSign`, `beginPlacing(signature:)`, `commitPlacement()` → `toolMode == .fillSign`, mode `.reading`); `cancellingADrawingKeepsMarkup`.
- [ ] Run `xcodebuild … test` — new tests fail.
- [ ] Add `ToolMode` and `toolMode`.
- [ ] Tests pass; commit.

### Task I2: Mode bar in the reader

**Files:** Modify `Features/Reader/ReaderView.swift`; Create `Features/Reader/ModeBar.swift` if the toolbar code grows past ~60 lines.

**Interfaces — Consumes:** `ReaderModel.toolMode` (I1).

- [ ] Replace the bottom `ToolbarItemGroup` with: `.none` → buttons "Markup" (`pencil.tip.crop.circle`), "Fill & Sign" (`signature`), "Pages" (`square.grid.2x2`, sets `showPages`), Undo. `.markup` → close (`xmark`), the existing "Mark Up Text" menu split into a Highlight menu (four colours) + Underline + Strikethrough buttons, Text, Draw, Undo. `.fillSign` → close, Signature, Text, Undo. Buttons show title + icon (`.labelStyle(.titleAndIcon)` or a VStack label); at accessibility Dynamic Type sizes fall back to icon-only with the title as accessibility label.
- [ ] Remove "Organize Pages" from the More menu.
- [ ] Build; open a document in a throwaway simulator; check each bar, sign twice in a row (Review Focus 1), check read-only document shows no mode buttons, check largest text size on iPhone 17 (Review Focus 5). Commit.

### Task I3: Tools tab and document picker

**Files:** Create `Features/Tools/ToolsView.swift`, `Features/Tools/DocumentPicker.swift`; Modify `App/PDFoundryApp.swift` (`AppTab.tools` between documents and settings, symbol `square.grid.2x2.fill`), `Features/Library/LibraryView.swift` (move scanner / photo / merge presentation into reusable pieces the Tools view can present, without changing the `+` menu's behaviour).

**Interfaces — Produces:** `enum PDFTool: CaseIterable { case scan, photos, merge, extract }` with `title`, `detail`, `symbol`, `tint`, `section`; `enum ToolSection { case create, combine, protect, enhance }` (only sections with tools render). `DocumentPicker(multiple: Bool, onPick: ([PDFItem]) -> Void)` lists `store.items` plus an "Open from Files" row at the top.

- [ ] Tiles: a white symbol on a coloured rounded tile, title, one-line detail, two columns on iPhone, adaptive on iPad. Scan only when `VNDocumentCameraViewController.isSupported`.
- [ ] Scan and Create from Photos run the same flows as the `+` menu; Merge opens `DocumentPicker(multiple: true)` then the existing merge sheet.
- [ ] Build, check by hand; commit.

### Task I4: Extract-only page picker

**Files:** Create `Features/Tools/ExtractPagesView.swift`; Modify `Core/DocumentStore.swift` only if a helper to save a new PDF is missing; Test `PDFoundryTests/ExtractPagesTests.swift`.

**Interfaces — Produces:** `func extractPages(from url: URL, pages: IndexSet) async throws -> PDFItem` on `DocumentStore` (reads with file coordination / security-scoped access for in-place files, writes a new library document named "<name> (pages)", never writes `url`).

- [ ] Tests: `extractingFromAFileOpenedInPlaceLeavesItUnchanged` (open a file in place from a temp folder, extract pages 1 and 3, source bytes equal before/after, new item has 2 pages) — Review Focus 4; `extractedDocumentKeepsPageOrder`.
- [ ] Run — fail. Implement using `PDFOperations.extract` (existing). Run — pass.
- [ ] View: grid of page thumbnails with checkmarks, Select All, "Extract" button disabled until ≥1 page; on success dismiss to Tools with a confirmation banner. Wire the Extract Pages tile through `DocumentPicker(multiple: false)`.
- [ ] Full test suite passes (15 existing + new); commit.

## Part 2: Android (branch `redesign/android`)

Key files: `MainActivity.kt` (`NavigationBar` ~line 122), `ui/ReaderScreen.kt` (`BottomAppBar` ~line 411, More menu ~line 378), `ui/PageEditor.kt` (ink / placement overlay), `ui/LibraryScreen.kt` (import, scan, photos, merge), `ui/PagesScreen.kt`, `data/PdfEngine.kt` (`overlay`, `ink`, `stamp`, `extract`, `search`), `data/Library.kt`. Build with `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. Instrumented tests need an emulator: create a throwaway AVD (API 35), never touch the AVD `cloudfile`, delete yours when done.

### Task A1: Text geometry and text markup in the engine

**Files:** Modify `data/PdfEngine.kt`; Test `androidTest/.../TextMarkupTest.kt` (new).

**Interfaces — Produces:**
- `data class TextWord(val text: String, val rect: RectF)` — `rect` in fractions of the displayed page from the top-left (same convention as `stamp`/`ink`), so rotation and crop box are already applied.
- `fun PdfEngine.words(file: File, pageIndex: Int): List<TextWord>` (PdfBox `PDFTextStripper` with `TextPosition`s, grouped into words).
- `enum class TextMark { Highlight, Underline, Strikethrough }`
- `fun PdfEngine.markText(file: File, pageIndex: Int, rects: List<RectF>, mark: TextMark, argb: Int)` — flattened through `overlay`: highlight = filled rect with multiply blend; underline = line at 92% of rect height, strikethrough = line at 50%, width ≈ 7% of rect height.

- [ ] Tests (generate fixtures in the test like `PdfEngineTest` does): `wordsAreFoundOnAPlainPage`; `wordsLandOnTheTextOnARotatedPage` (90°) and `…OnACroppedPage` (Review Focus 2: render the page with `PdfRenderer` after `markText` Highlight and check the pixels under the word's rect changed and pixels outside did not); `aScannedPageHasNoWords` (image-only page → empty list, Review Focus 3); `underlineAndStrikethroughDrawLines`.
- [ ] Run on the emulator — fail. Implement. Run — pass. Commit.

### Task A2: Reader modes, text selection and highlighter pen

**Files:** Modify `ui/ReaderScreen.kt`, `ui/PageEditor.kt`; Create `ui/ModeBar.kt`, `ui/TextSelection.kt`; Modify `ReaderState` (where it lives) ; Test `androidTest/.../ReaderModeTest.kt`.

**Interfaces — Consumes:** A1. **Produces:** `enum class ToolMode { None, Markup, FillSign }`, `ReaderState.toolMode` (saved across configuration changes the same way as the rest of `ReaderState`), `EditMode.TextMark(mark: TextMark, argb: Int)`.

- [ ] Tests: `placingASignatureKeepsFillSign`, `finishingADrawingKeepsMarkup`, `toolModeStartsAtNone` (Review Focus 1).
- [ ] Run — fail. Implement state. Run — pass.
- [ ] `ModeBar`: reading → Markup, Fill & Sign, Pages, Undo (labelled `ToolButton`s); Markup → close, Highlight (dropdown Yellow/Green/Pink/Blue), Underline, Strikethrough, Text, Draw, Undo; Fill & Sign → close, Signature, Text, Undo. Remove "Organize pages" from the More menu; Pages opens `PagesScreen`. Read-only keeps the lock notice.
- [ ] `TextSelection`: with `EditMode.TextMark` active, load `words()` for the visible page off the main thread; drag selects whole words between start and current point in reading order; tinted overlay; on lift call `state.edit { markText(...) }` (existing push/undo path). No words → snackbar "This page has no text to mark up. Use Draw instead." 
- [ ] Draw gains a "Highlighter" pen (today's `EditMode.Ink(highlighter = true)` behaviour) next to the existing colours, so the old freehand highlight is still there.
- [ ] Emulator by hand: sign twice, highlight/underline/strike selected words on a normal and a rotated page, a scanned page shows the hint, largest font scale fits (Review Focus 5). Commit.

### Task A3: Tools tab, document picker, extract-only pages

**Files:** Create `ui/ToolsScreen.kt`, `ui/DocumentPickerSheet.kt`, `ui/ExtractPagesScreen.kt`; Modify `MainActivity.kt` (third `NavigationBarItem` "Tools", `Icons.Filled.GridView`, between Documents and Settings), `ui/LibraryScreen.kt` (expose scan / photos / merge launchers for reuse; `+` menu behaviour unchanged), `data/Library.kt` (`extractToLibrary(source: File, pages: List<Int>): File` if missing); Test `androidTest/.../ExtractPagesTest.kt`.

- [ ] Test `extractingFromAFileOpenedInPlaceLeavesItUnchanged` (folder-backed `OutsideFiles` as in `OpenInPlaceTest`; md5 of the real file before/after equal; new library file has the chosen pages) — Review Focus 4.
- [ ] Run — fail. Implement. Run — pass.
- [ ] Tools screen tiles as in Global Constraints (Scan only where the ML Kit scanner is available; it needs no permission). Merge → picker (multiple) → existing merge flow. Extract → picker (single) → `ExtractPagesScreen` (thumbnails via `PdfRenderer`, checkboxes, Extract) → back to Tools with a snackbar.
- [ ] All instrumented tests pass (28 existing + new); delete the throwaway AVD; commit.

## Part 3: Desktop (branch `redesign/desktop`)

Key files: `desktop/src/ui/app.ts` (rail), `src/ui/reader.ts` (toolbar ~line 380–412, ink/highlight, placement, ⋯ menu ~731), `src/ui/organizer.ts`, `src/ui/library.ts`, `src/ui/pdfview.ts` (pdf.js display), `src/core/pdf.ts` (engine), `src/ui/demo.ts` + `scripts/selftest.mjs` (self-test; required count is hard-coded there), `src/main/main.ts` (file dialogs, recent list allow-list). Commands in `desktop/`: `npm test`, `npm run selftest`.

### Task D1: Text markup in the engine

**Files:** Modify `src/core/pdf.ts`; Test `test/textmark.test.ts` (new).

**Interfaces — Produces:** `type TextMark = 'highlight' | 'underline' | 'strikethrough'`; `markText(bytes: Uint8Array, pageIndex: number, rects: PageRect[], mark: TextMark, rgba: [number, number, number, number]): Promise<Uint8Array>` where `PageRect` is the engine's existing page-fraction rect type (top-left origin, displayed page, rotation/crop applied — reuse what `stamp` uses). Highlight = multiply blend (as today's highlight); line positions/width as in Task A1.

- [ ] Tests: `marksLandOnTheRectOnARotatedPage`, `…OnACroppedPage` (Review Focus 2; check the resulting drawing operators / rect in PDF user space), `underlineAndStrikethroughAreLines`.
- [ ] Run `npm test` — fail. Implement. Pass. Commit.

### Task D2: Reader modes and text selection

**Files:** Modify `src/ui/reader.ts`, `src/ui/pdfview.ts` (enable the pdf.js text layer for selection); Create `src/ui/modebar.ts`; Modify `src/ui/demo.ts`, `scripts/selftest.mjs`.

**Interfaces — Consumes:** D1. **Produces:** `type ToolMode = 'none' | 'markup' | 'fillSign'`, `reader.toolMode`.

- [ ] Self-test checks first (they fail before the change): `signTwiceStaysInFillSign` (Review Focus 1); `highlightSelectedText`, `underlineSelectedText`, `strikethroughSelectedText` (pixel checks on selected words, including the rotated sample page); `scanPageShowsNoTextHint` (Review Focus 3). Raise the required count in `scripts/selftest.mjs` accordingly.
- [ ] Floating bar: reading → Markup, Fill & Sign, Pages, Undo; Markup → ×, Highlight (colour menu), Underline, Strikethrough, Text, Draw, Undo; Fill & Sign → ×, Signature, Text, Undo. Pages opens the organizer; the header page-grid button and ⋯ "Organize pages" stay. Selecting text in a text tool applies the mark on mouse-up through the existing edit/undo/dirty path. Draw gains a "Highlighter" pen (today's freehand highlight).
- [ ] `npm test` and `npm run selftest` (incl. `--reopen`) pass. Commit.

### Task D3: Tools in the rail, picker, extract-only pages

**Files:** Create `src/ui/tools.ts`, `src/ui/extract.ts`; Modify `src/ui/app.ts` (rail item "Tools" between Files and Settings), `src/ui/library.ts` (reuse merge / create-from-images flows), `src/main/main.ts` only if a dialog or read handler is missing; Modify `src/ui/demo.ts`, `scripts/selftest.mjs`; Test `test/extract.test.ts`.

- [ ] Test `extractLeavesTheSourceUnchanged` (source file bytes equal before/after; output has the chosen pages) — Review Focus 4.
- [ ] Tools view tiles: Create from Images, Merge PDFs, Extract Pages (no Scan). Picker dialog lists recent files plus "Browse…". Extract: thumbnails with checkboxes, Extract → save dialog → back to Tools with a short confirmation. Extract does not add the source to the recent list.
- [ ] Self-test check `toolsTabExtractsPages`; update `npm run screenshots` targets if they name the rail.
- [ ] `npm test`, `npm run selftest` pass. Commit.

## Part 4: Integration and release (Claude, after review)

- [ ] Review each branch against the spec; fix or send back; merge `redesign/ios`, `redesign/android`, `redesign/desktop` into `design/tools-redesign` (PR #2).
- [ ] Bump Android `versionName` to 1.0.4, `desktop/package.json` to 1.2.0; write release notes (`AppStore/whatsnew/en-US.txt`, `android/whatsnew/whatsnew-en-US`).
- [ ] New screenshots (iPhone 6.9", iPad 13", Android phone, desktop via `npm run screenshots`); update README and website text for the Tools tab and modes.
- [ ] CI green on the PR; merge to `main` (user approved push and release); release: iOS workflow manual run with upload, tag `android-1.0.4`, tag `desktop-1.2.0`.
- [ ] Stop simulators/emulators started for testing, delete throwaway devices; update `CLAUDE.md`.
