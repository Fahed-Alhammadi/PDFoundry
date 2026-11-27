# Fill & Sign, Split PDF and Password-protect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add form filling, Initials, Date and ✓/✗ to Fill & Sign, and Split PDF and Password-protect (with editing of protected files) to the Tools tab, on iOS, Android and desktop.

**Architecture:** Three independent parts, one per platform, each on its own branch built by its own implementer (they share no code). Each part adds engine functions first (split, protect/unlock, fields, stamps), tested without UI, then wires them into the existing Tools grid, Fill & Sign bar and reader. The password is held in memory by the open reader and re-applied on every save.

**Tech Stack:** iOS: SwiftUI (iOS 26), PDFKit, Swift Testing, XcodeGen. Android: Kotlin, Compose Material 3, pdfbox-android, instrumented tests. Desktop: Electron 45, TypeScript, pdf.js 6.5, @cantoo/pdf-lib 2.11, node tests + `npm run selftest`.

**Spec:** `docs/superpowers/specs/2026-11-27-fill-sign-split-password-design.md` (read it first; this plan does not repeat it).

## Global Constraints

- Fill & Sign bar: `×  Signature  Initials  Text  Date  ✓  ✗   ↶`. Names: Initials, Date, Check (✓), Cross (✗) as accessibility labels. Bar stays in Fill & Sign after placing.
- Tools: **Split PDF** in Combine & split (detail line "Make several PDFs from one"); **Password-protect** in Protect & optimize (detail "Lock a PDF with a password"). Protect & optimize now shows; Enhance stays hidden.
- Reader More menu: **Protect with Password** (editable, unprotected) / **Remove Password** (protected and unlocked with full access).
- Warning text, exactly: "If you forget the password, the file can't be opened, and it can't be recovered."
- Split output names `<name> (part 1)`, `(part 2)`…; Split and Extract never write the source and never list it.
- Encryption: user password = owner password = the entered one, all permissions. iOS: PDFKit (AES-128). Android and desktop: AES-256.
- Full access = the entered password is the owner password (or user = owner). Otherwise, and for restriction-only files, read-only as today.
- The password lives only in memory of the open document. Never on disk, in logs, preferences, recent lists or undo files.
- Form values are written as field values with appearances; nothing flattens fields. Non-Latin values get an app-drawn appearance image (spec §2).
- Tool wins over field taps while a placement/drawing/markup tool is active.
- Merge still refuses protected sources; its message ends "Use More › Remove Password first."
- No new permissions, no new third-party dependencies, no onboarding screens. Do not change version numbers (release step does it).
- Every existing test keeps passing.

### Shared definitions (each platform implements its own copy)

- `parseRanges(text, pageCount) -> [[Int]]` (0-based page lists) or an error message. Accepts `n` and `a-b` separated by commas, spaces ignored. Errors: empty text → "Enter page ranges, such as 1-3, 4-6."; a number < 1 or > pageCount → "Page N doesn't exist. This PDF has M pages."; `a-b` with a > b → "Write ranges from low to high, such as 3-5."; anything else → "Use numbers, dashes and commas, such as 1-3, 4-6.". Overlaps allowed.
- `everyN(pageCount, n) -> [[Int]]`: consecutive chunks of n, last one shorter; n ≥ 1. Every page = `everyN(count, 1)`.
- Test vectors for all three: `"1-3, 4-6, 9"`/10 → `[[0,1,2],[3,4,5],[8]]`; `"2"`/2 → `[[1]]`; `""`, `"0"`, `"5-3"`, `"1-11"`/10, `"a"` → the four errors above; `everyN(5,2)` → `[[0,1],[2,3],[4]]`.

## Review Focus

1. **A protected file stays protected through every write path**: edit, Undo, field change, Save As (desktop), Revert to an encrypted original. Test per platform: open protected → edit → undo → reload → still needs the password, opens with it, content as expected.
2. **A save that fails halfway on a protected file** (Android push to an in-place file fails; desktop write error) leaves the real file protected with its previous content, never plain. Android test with a failing `OutsideFiles` write; desktop store test.
3. **Protecting the file that's currently open** (desktop: open in the reader, maybe with unsaved edits; phones: protect from the reader's More menu) — the reader must take the password at once so its next save is encrypted. Test per platform: protect while open → make an edit → file still needs the password.
4. **Arabic value in a text field** — saved value equals the typed string, appearance is not blank (iOS: PDFKit render of the page has dark pixels in the field rect; Android: bitmap of rendered page; desktop: self-test pixel check).
5. **Bad split input** — the five error vectors above disable Split and show the message; no file is written. Unit tests on `parseRanges` + one UI/self-test check.

---

## Part 1: iOS (branch `features/ios`)

Key files: `Features/Reader/ReaderModel.swift` (`canEdit`, `unlock(with:)`, `save()`, `revertToOriginal()`, `commitPlacement()`, `beginPlacing`), `Features/Reader/ModeBar.swift`, `ReaderView.swift` (More menu, locked view), `Features/Tools/ToolsView.swift` (`PDFTool`, `ToolSection.shown`), `DocumentPicker.swift`, `ExtractPagesView.swift`, `Core/DocumentStore.swift` (`extractPages`, `open`, `save`, `uniqueURL`), `Core/PDFOperations.swift`, `Core/SignatureStore.swift`, `Features/Sign/SignatureViews.swift`. Run `xcodegen generate` after adding files. Tests in `PDFoundryTests/`.

### Task I1: Split and ranges engine

**Files:** Create `Core/PageRanges.swift`; modify `Core/DocumentStore.swift`; Test `PDFoundryTests/SplitTests.swift`.
**Produces:** `enum PageRanges { static func parse(_ text: String, pageCount: Int) -> Result<[[Int]], RangeError>; static func every(_ n: Int, pageCount: Int) -> [[Int]] }` (`RangeError: LocalizedError` with the four messages); `DocumentStore.split(_ url: URL, into parts: [[Int]], password: String?) async throws -> [PDFItem]`.
- [ ] Tests: the shared vectors; `splitWritesPartsAndLeavesSourceUnchanged` (4-page file, `[[0,1],[2,3]]` → two items named "X (part 1)", "X (part 2)", page counts 2/2, source bytes equal before/after, source not in library); `splitOfProtectedFileKeepsPassword` (parts are `isEncrypted`, unlock with the password).
- [ ] Run tests — fail. Implement (reuse `PDFOperations.extract`, `uniqueURL`, write with password options when given). Tests pass. Commit.

### Task I2: Passwords in the reader and engine

**Files:** Modify `ReaderModel.swift`, `PDFOperations.swift`, `DocumentStore.swift`; Test `PDFoundryTests/PasswordTests.swift`.
**Produces:** `PDFOperations.writeOptions(password: String?) -> [PDFDocumentWriteOption: Any]`; `ReaderModel.password: String?` (private(set)); `canEdit` = not encrypted, or unlocked with `permissionsStatus == .owner`; `save()`/`revertToOriginal()` write with `writeOptions(password)`; `ReaderModel.protect(with: String) -> Bool`, `removePassword() -> Bool`; `DocumentStore.protect(_ url: URL, password: String) async throws` (coordinated in-place write, refuses an already-encrypted file with `PDFoundryError.alreadyProtected`).
- [ ] Tests: `unlockedWithOwnerPasswordCanEdit`; `restrictionOnlyFileStaysReadOnly` (owner pw "o", user "" → `canEdit == false`); `editOfProtectedFileStaysProtected` (Review Focus 1: add stamp, undo, add again, reload from disk → `isLocked`, unlock with pw, 1 stamp); `protectWhileOpenThenEditStaysProtected` (Focus 3); `removePasswordWritesPlainFile`; `protectRefusesAlreadyProtected`.
- [ ] Fail → implement → pass. Commit.

### Task I3: Initials, Date, ✓ and ✗

**Files:** Modify `SignatureStore.swift` (add `kind: SignatureKind` = `.signature`/`.initials`, folders `Signatures`/`Initials`), `SignatureViews.swift` (title "Initials" when kind is initials), `ModeBar.swift`, `ReaderModel.swift`; Create `Core/Marks.swift`; Test `PDFoundryTests/FillSignMarksTests.swift`.
**Produces:** `enum Mark { case check, cross; func image(side: CGFloat = 512) -> UIImage }` (SF Symbol `checkmark` / `xmark`, bold, `UIColor.label` resolved to dark `#12141C`); `ReaderModel.todayText(_ date: Date = .now, locale: Locale = .current) -> String` (DateFormatter `.medium`, no time); `beginPlacing(mark: Mark)`, `beginPlacingDate()` (as `beginPlacing(text:)` with today's text, editable before placing).
- [ ] Tests: `todayTextUsesMediumStyle` (2026-11-27, en_US → "Nov 27, 2026"); `initialsAreStoredApartFromSignatures`; `checkStampSurvivesSaveOnRotatedPage` (page rotated 90°, stamp, write, reload, one stamp annotation with the expected bounds); `fillSignStaysAfterPlacingDate`.
- [ ] Fail → implement; bar order per Global Constraints; on compact width when items don't fit, ✓ and ✗ become one "Mark" menu. Pass. Commit.

### Task I4: Form fields

**Files:** Modify `ReaderModel.swift`, `ReaderCanvas.swift`; Create `Core/FormFields.swift`; Test `PDFoundryTests/FormFieldTests.swift` with a fixture built in code (text, multiline text, checkbox, radio pair, choice, signature field) via `PDFAnnotation` widgets.
**Produces:** `FormFields.set(_ widget: PDFAnnotation, value: FieldValue)` where `enum FieldValue { case text(String), on(Bool), choice(String) }`; ReaderModel registers each committed field change on `undoStack` (one step; undo restores the old value) and calls `save()`. Detection of a committed change is the builder's call (e.g. observe `PDFView` annotation-hit + text-end editing, or KVO on `widgetStringValue`/`buttonWidgetState`). Signature field tap → signature list → stamp fitted into the widget bounds (aspect kept, centred). While `mode != .reading` (placing/drawing) the overlay already covers the PDFView, so the tool wins; also disable widget interaction while a markup text tool is armed.
- [ ] Tests: `fieldValuesSurviveSaveAsFields` (each kind set, write, reload → values equal, still `.widget` annotations); `arabicFieldValueRoundTrips` (value "مرحبا بالعالم" equal after reload; page render has dark pixels inside the field rect — Focus 4); `fieldChangeIsUndoable`; `readOnlyFieldIsNotChanged`.
- [ ] Fail → implement → pass. Commit.

### Task I5: Tools tiles, sheets and menus

**Files:** Modify `ToolsView.swift` (`PDFTool.split`, `.protect`; section `.protect` shown), `DocumentPicker.swift` (Open from Files for `.protect` calls `DocumentStore.open` — in place, listed), `ExtractPagesView.swift` (password prompt for protected source; output keeps password); Create `Features/Tools/SplitView.swift`, `ProtectView.swift`; modify `ReaderView.swift` (More: Protect with Password / Remove Password with confirm); modify Merge's locked message. Tests: extend `ToolsCatalogTests.swift`.
- [ ] Tests: `catalogShowsSplitAndProtect` (Combine & split = Merge, Extract, Split; Protect & optimize = Password-protect; Enhance hidden); `splitButtonDisabledForBadRanges` (view-model level, Focus 5).
- [ ] Implement: Split sheet (Every page / Every N pages (stepper, default 2) / Custom ranges; "Makes K files" line; error line); Protect sheet (Password + Confirm `SecureField`s, the warning text, Protect disabled until equal and non-empty; already protected → message only). Confirmation toasts as Extract's.
- [ ] Build; in a throwaway simulator: split a 5-page file every 2 pages, custom ranges with each bad input, protect a file opened in place from Files, reopen it (asks), edit, reopen (still asks), Remove Password, fill a form (use a fixture PDF with fields generated by the test helper and dropped into the simulator's Files), Arabic in a field, sign twice in a row. Delete the simulator. Commit.

---

## Part 2: Android (branch `features/android`)

Key files: `data/PdfEngine.kt` (`edit`, `extract`, `unlock`, `stamp`, `textBitmap`), `data/Library.kt` (`DocumentStore.open`, `push`, `extractPages`, `temporaryCopy`), `data/OutsideFiles.kt`, `data/Stores.kt` (`SignatureStore`), `ui/ReaderScreen.kt` (`ReaderState`: `readOnly`, `unlock`, `edit`, `undo`, `revertToOriginal`, `place`), `ui/ModeBar.kt`, `ui/ToolsScreen.kt`, `ui/DocumentPickerSheet.kt`, `ui/ExtractPagesScreen.kt`. Tests: `android/app/src/androidTest/...` (run on a throwaway API 35 AVD; never touch AVD `cloudfile`). Build with `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.

### Task A1: Split and ranges engine

**Files:** Create `data/PageRanges.kt`; modify `PdfEngine.kt`, `Library.kt`; Test `androidTest/SplitTest.kt` (ranges tests may be JVM unit tests in `src/test` if the module has them; otherwise instrumented).
**Produces:** `object PageRanges { fun parse(text: String, pageCount: Int): RangeResult; fun every(n: Int, pageCount: Int): List<List<Int>> }` with `sealed interface RangeResult { data class Ok(val parts: List<List<Int>>); data class Bad(val message: String) }`; `PdfEngine.split(source: File, parts: List<List<Int>>, destinations: List<File>, password: String? = null)`; `DocumentStore.split(source: File, parts: List<List<Int>>, password: String?): List<File>` (names `<name> (part k).pdf`, unique names, source never written or listed; reads the working copy like `extractPages`).
- [ ] Tests: shared vectors; `splitLeavesSourceUnchanged` (md5 before/after); `splitOfProtectedFileKeepsPassword`. Fail → implement → pass. Commit.

### Task A2: Passwords: engine, ReaderState and Library

**Files:** Modify `PdfEngine.kt`, `Library.kt`, `ReaderScreen.kt`; Test `androidTest/PasswordTest.kt`.
**Produces:** `PdfEngine.protect(source: File, password: String, destination: File)` (AES-256, `StandardProtectionPolicy(pw, pw, AccessPermission())`, key length 256); `PdfEngine.unlock(source, password, destination): UnlockResult` (`Full`, `Limited`, `Wrong`) replacing the Boolean; `PdfEngine.isProtected(file): Boolean`; `DocumentStore.protect(file: File, password: String)` (protects the library file or the working copy and pushes to the real file). `ReaderState`: on `Full` unlock keep `password` in memory and an unlocked copy `plain` in `cacheDir/unlocked/<id>.pdf`; `edit` changes `plain`, then writes `protect(plain, password, file)` and pushes; undo snapshots and the Revert original stay in cache (deleted on close as today); if protect or push fails, `file` keeps its previous encrypted bytes. `ReaderState.protect(password)`, `removePassword()`.
- [ ] Tests: `fullAccessUnlockCanEdit`; `limitedAccessStaysReadOnly`; `editOfProtectedFileStaysProtected` (Focus 1: stamp, undo, stamp, reload → needs pw, one stamp); `failedPushKeepsFileProtected` (Focus 2, failing `OutsideFiles`); `protectWhileOpenThenEditStaysProtected` (Focus 3); `removePasswordWritesPlainFile`; `plainCopyIsDeletedOnClose`.
- [ ] Fail → implement → pass. Commit.

### Task A3: Initials, Date, ✓ and ✗

**Files:** Modify `Stores.kt` (`SignatureStore(context, folder: String = "signatures")`; initials use `"initials"`), signature pad UI title, `ModeBar.kt`, `ReaderScreen.kt`; Create `data/Marks.kt`; Test `androidTest/FillSignMarksTest.kt`.
**Produces:** `enum class Mark { Check, Cross }` with `fun Mark.bitmap(side: Int = 512): Bitmap` (paths drawn in `#12141C`, round caps); `fun todayText(date: Date = Date(), locale: Locale = Locale.getDefault()): String` via `DateFormat.getDateInstance(DateFormat.MEDIUM, locale)`. Date uses the Text flow (text field prefilled, rendered by `textBitmap`). Narrow screens: ✓/✗ fold into one "Mark" menu (`ToolButton` with trailing arrow).
- [ ] Tests: `todayTextIsMediumStyle` (en_US 2026-11-27 → "Nov 27, 2026"); `initialsStoredApart`; `checkStampOnRotatedPageLandsInRect` (as existing overlay tests); `fillSignStaysAfterPlacing` (extend `ReaderModeTest`). Fail → implement → pass. Commit.

### Task A4: Form fields

**Files:** Create `data/FormFields.kt`, `ui/FieldLayer.kt`; modify `ReaderScreen.kt` (`PageList` adds the layer under the selection/placement layers); Test `androidTest/FormFieldTest.kt` with a fixture built by PdfBox in the test (text, multiline, checkbox, radio pair, choice, signature, read-only text).
**Produces:** `data class FormField(val name: String, val kind: FieldKind, val page: Int, val rect: RectF /* page fraction, displayed, top-left */, val value: String, val options: List<String>, val onValue: String?, val readOnly: Boolean)`; `enum class FieldKind { Text, MultilineText, Checkbox, Radio, Choice, Signature }`; `PdfEngine.fields(file: File): List<FormField>` (rects through the same rotation/crop mapping as `overlay`); `PdfEngine.setField(file: File, name: String, value: String, widgetIndex: Int = 0)` — sets `/V`, then builds appearances with `PDAcroForm.refreshAppearances`/field `setValue`; when the field font can't encode the value (catch `IllegalArgumentException` from encoding), writes an appearance stream that draws `textBitmap(value)` scaled into the widget rect. `ReaderState.setField(...)` goes through `edit` (so undo, autosave and password apply). `FieldLayer` draws tinted boxes + Compose inputs (BasicTextField commit on focus loss/IME Done, checkbox/radio toggle on tap, dropdown menu for choice, signature → signature sheet then `place` fitted to the widget rect). The layer ignores input while `EditMode` is not reading or a text tool is armed (tool wins).
- [ ] Tests: `fieldsAreListedWithRects`; `fieldValuesSurviveAsFields` (each kind, reload, values equal, `acroForm.fields` still present, not flattened); `arabicFieldValueRoundTrips` (value equal, rendered page bitmap has dark pixels in rect — Focus 4); `readOnlyFieldUnchanged`; `fieldChangeIsUndoable`.
- [ ] Fail → implement → pass. Commit.

### Task A5: Tools tiles, screens and menus

**Files:** Modify `ToolsScreen.kt` (`PdfTool.Split`, `PdfTool.Protect`, section Protect shown), `DocumentPickerSheet.kt` (for Protect, "Open PDF" uses `DocumentStore.open` — persistable write permission, listed; never `temporaryCopy`), `ExtractPagesScreen.kt` (password prompt; output keeps password); Create `ui/SplitScreen.kt`, `ui/ProtectSheet.kt`; modify reader More menu; Merge's protected message.
- [ ] Tests: extend tools catalogue test (sections and order); `splitScreenRejectsBadRanges` (Compose UI test, Focus 5).
- [ ] Implement per spec. Build, install on a throwaway API 35 AVD, check by hand: split (every page, every 2, custom + bad inputs), protect a file in Documents opened via the system picker, reopen (asks), edit, Remove Password, fill a fixture form incl. Arabic, sign twice. Delete the AVD. Commit.

---

## Part 3: Desktop (branch `features/desktop`)

Key files: `src/core/pdf.ts` (`extract`, `stamp`, `isEditable`, load helper), `src/ui/reader.ts` (open with password prompt, edit snapshots, Save/Save As), `src/ui/pdfview.ts`, `src/ui/modebar.ts`, `src/ui/sign.ts`, `src/ui/tools.ts`, `src/ui/extract.ts` (`pickDocuments`, `extractPages`), `src/main/store.ts` (`saveNew(path, bytes, keep)`, `save`, `readOnce`), `src/main/main.ts` (IPC), `src/ui/demo.ts` + `scripts/selftest.mjs` (required check count). Tests: `desktop/test/*.test.ts` (`npm test`), `npm run selftest`.

### Task D1: Split, protect and unlock engine

**Files:** Create `src/core/ranges.ts`; modify `src/core/pdf.ts`; Test `test/split.test.ts`, `test/password.test.ts`.
**Produces:** `parseRanges(text: string, pageCount: number): { parts: number[][] } | { error: string }`; `everyN(pageCount: number, n: number): number[][]`; `pdf.split(bytes: Uint8Array, parts: number[][]): Promise<Uint8Array[]>`; `pdf.unlock(bytes: Uint8Array, password: string): Promise<{ bytes: Uint8Array; full: boolean } | null>` (null = wrong; `bytes` decrypted); `pdf.protect(bytes: Uint8Array, password: string): Promise<Uint8Array>` (pdf-lib `encrypt`, AES-256, user = owner, all permissions); `pdf.isProtected(bytes): Promise<boolean>`.
- [ ] Tests: shared vectors; `split` page counts; `protect` → pdf.js open fails without password, opens with it (use `pdfjs-dist/legacy` in node, as existing tests do or via pdf-lib `load` with `password`); `unlock` full vs limited (owner "o"/user "u" → `full:false` with "u", `true` with "o"); round trip protect → unlock → bytes load.
- [ ] Fail → implement → pass. Commit.

### Task D2: Passwords in the reader, Tools and store

**Files:** Modify `reader.ts`, `tools.ts`, `extract.ts`, `store.ts`, `main.ts`; Create `src/ui/protect.ts`, `src/ui/split.ts`; Test `test/split.test.ts` (store), self-test checks.
**Produces:** reader keeps `password: string | null` and works on decrypted bytes; Save and Save As write `pdf.protect(bytes, password)` when set; Undo snapshots are of decrypted bytes in memory. More (⋯) menu: Protect with Password / Remove Password. `ToolsHost` gains `protectedOpen(file: string, password: string): void` so Tools tells the reader when it protects the open file (Focus 3); Protect of the open file with unsaved edits asks "Save and Protect / Cancel". Split asks for a folder (`dialog.showOpenDialog` directory) via new IPC `pickFolder`, writes parts with `Store.saveNew(join(folder, name), bytes, [source])`, existing names → one question Replace / Keep both / Cancel. Browse… in Protect's picker adds the file to recent (in place), unlike Extract's `readOnce`. Tools sections: `PdfTool` adds `'split' | 'protect'`. Extract and Split of a protected source ask for its password (full access) and write their output with `pdf.protect(…, password)`. XFA-only forms show no fields (all platforms).
- [ ] Tests: `splitNeverWritesTheSource` (store, like `extract.test.ts`); `aFailedProtectedSaveLeavesTheOldFile` (store write throws mid-way → file still old encrypted bytes — Focus 2; write to temp then rename if `Store.save` doesn't already).
- [ ] Implement. Commit.

### Task D3: Initials, Date, ✓, ✗ and form fields

**Files:** Modify `modebar.ts`, `sign.ts` (kind `'signature' | 'initials'`, stored apart in main), `reader.ts`, `pdfview.ts` (render pdf.js `AnnotationLayer` with `annotationMode: AnnotationMode.ENABLE_FORMS` per page), `pdf.ts`; Test `test/forms.test.ts`.
**Produces:** `todayText(date = new Date(), locale = navigator.language): string` (`Intl.DateTimeFormat(locale, { dateStyle: 'medium' })`); `markPng(mark: 'check' | 'cross', side = 512): Promise<Uint8Array>` (canvas paths, `#12141C`); `pdf.fields(bytes): Promise<FieldInfo[]>` (`{ name, kind: 'text' | 'multiline' | 'checkbox' | 'radio' | 'choice' | 'signature', page, rect: PageRect, value, options, readOnly }`); `pdf.fillFields(bytes, values: Record<string, string | boolean>, images?: Record<string, Uint8Array>): Promise<Uint8Array>` — sets values with pdf-lib's form API and `updateFieldAppearances`; for a name in `images` (non-Latin text drawn by the window) sets that PNG as the widget's normal appearance. The reader reads committed values from the pdf.js annotation storage on change/blur, pushes one undo snapshot per committed change, marks the document unsaved. While a tool is armed, the annotation layer gets `pointer-events: none` (tool wins). Signature field click → signature dialog → stamp fitted into the field rect.
- [ ] Tests: `todayText` (en-US 2026-11-27 → "Nov 27, 2026"); `fillFields` keeps fields (each kind reloads with the value, `getForm().getFields().length` unchanged); Arabic value with an image appearance reloads with `/V` equal; read-only field rejected.
- [ ] Self-test (`demo.ts`): new checks — Initials and Date placed; ✓ pixel check; type into a text field + Save → reload value; Arabic field pixel check (Focus 4); bad split range disables Split (Focus 5); protect → reopen asks → edit → Save → still protected (Focus 1, 3). Update the required count in `scripts/selftest.mjs` to the new total.
- [ ] `npm test` and `npm run selftest` pass on macOS. Commit.

---

## Part 4: Integration (Claude, on `main`)

- [ ] Independent code review per branch (correctness, security of the password handling, anti-goals); fix findings; rerun each platform's tests.
- [ ] Merge the three branches; CI green on iOS, Android, desktop (macOS, Windows, Linux).
- [ ] Release notes: `AppStore/whatsnew/en-US.txt`, `android/whatsnew/whatsnew-en-US`. Versions: Android `versionName` 1.0.5; `desktop/package.json` 1.3.0; iOS stays 1.0.0 (check App Store state first; if approved, ask the user about 1.1.0).
- [ ] Screenshots (Fill & Sign bar with a form, Tools with the new tiles, split and protect sheets) on iPhone 6.9", iPad 13", Android phone, desktop; README feature/usage tables and website cards; no signing details in public docs.
- [ ] PR, then on the user's approval: merge, tag `android-1.0.5`, `desktop-1.3.0`, run the iOS upload workflow. Clean up worktrees, throwaway simulators/AVDs, and update the project `CLAUDE.md`.
