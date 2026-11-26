# Tools redesign: reader modes and a Tools tab

Date: 2026-11-26. Platforms: iOS/iPadOS, Android, desktop (Electron).

## Why

A list of new features is planned: form filling, date stamps, initials, check marks, shapes, comments,
redaction, OCR, split, compress, password protection, page numbers, watermark, export as images. Today's
screens have no room for them. The phone reader has one bottom bar of five unlabelled buttons
(mark up, text, draw, sign, undo), and the whole-file actions are hidden behind `+`, Select and a card's
context menu. The user is not unhappy with the screens as they are. The worry is that the features won't
fit, so this change builds a structure where every planned feature already has a place.

## Goals

- Every tool on the feature list has an obvious home, on all three apps, without crowding a screen.
- The same structure everywhere: someone who knows the phone app finds things in the desktop app.
- Reading stays as simple as today: open a file and read, with no new steps.
- Tool buttons carry labels (today's phone bar has icons only).

## Non-goals

- New features. This release moves **today's** tools into the new structure. Each new feature
  ships later in its own release, into the slot this design gives it. **One exception, the user's
  decision:** Android and desktop get text markup (see 2a) so Markup is the same on all three apps.
- A new visual style. Liquid Glass on iOS, Material 3 on Android, the current desktop look; brand
  colours graphite, steel and ember stay.
- Changes to how files are opened, saved, autosaved or reverted.

## Anti-goals (a failure even if it works)

- Signing a document takes noticeably more taps than today.
- A mode that holds one button, or a tab of empty "coming soon" tiles.
- The phones and desktop drifting apart in names or grouping.
- Any pre-permission or onboarding screen (App Review 5.1.1(iv)); the camera is still asked for only
  by the system scanner when the user taps Scan.

## Approaches considered

- **A. Modes in the reader** (chosen): a bottom bar picks a mode; each mode shows only its own tools.
  Scales cleanly; costs one tap to enter a mode.
- **B. Favourites plus an "All tools" sheet**: smallest change now, but tools in the sheet get used
  less and it would likely be redone after the next round of features.
- **C. A Tools tab on the home screen** (chosen, together with A): a grid of whole-file tools. On its
  own it doesn't help the reader, so it is paired with A.

The user picked **A + C**.

## Design

### 1. Home: Documents, Tools, Settings

**Phones** get a third tab between Documents and Settings: `Documents | Tools | Settings`.
**Desktop** gets a third item in the side rail: `Files | Tools | Settings`.

The Documents tab is unchanged, including `+` (Open, Scan, Create from Photos), Select and Sort,
because those create or manage documents and people already know them there.

The **Tools** tab is a grid of tool tiles in sections. Each tile has a white symbol on a coloured rounded
tile, a title and one short line of description (the style of the Settings rows, larger). Sections and
the tools that exist **today**:

| Section | Tools now | Later features slot in here |
|---|---|---|
| Create | Scan Document (phones only), Create from Photos / Images | Export as images |
| Combine & split | Merge PDFs, Extract Pages | Split PDF |
| Protect & optimize | (section hidden until it has a tool) | Password, Compress, Redact |
| Enhance | (hidden) | OCR, Page numbers, Watermark |

A section with no tools yet is not shown.

**Running a tool that needs files:** tapping the tile opens a document picker (a sheet on phones, a
dialog on desktop) listing the library / recent files, with "Open from Files" (phones) or "Browse…"
(desktop) at the top. Merge allows several files and then shows today's merge sheet. Extract Pages takes one
file and opens an **extract-only** page picker: page thumbnails to tick, and one "Extract" button. It
never changes the source file (no delete, rotate or reorder there, because on phones every change
autosaves into the real file, including files opened in place). It does not open the reader and does
not add the source to the library or recent list. The new PDF is saved the way each app saves today
(phones: into the library; desktop: asks where to save), and the user returns to the Tools tab with a
short confirmation. *(Builder-check call, made by Claude.)*

### 2. Reader: modes

The reader's bottom bar, while reading, shows **three labelled mode buttons and Undo**:

```
[ ✎ Markup ]  [ ✍︎ Fill & Sign ]  [ ▦ Pages ]        [ ↶ ]
```

- **Markup** swaps the bar for its tools, with a close (×) button on the left:
  `×  Highlight▾  Underline  Strikethrough  Text  Draw   ↶`
  Highlight keeps its colour menu. Later: shapes, comments, redaction.
- **Fill & Sign**: `×  Signature  Text   ↶`. Later: form fields, date, initials, ✓ / ✗.
  Text appears in both modes on purpose: people look for it in both places.
- **Pages** is not a mode: it opens today's page organizer directly (moved here from the More menu),
  because the organizer is already a full screen of page tools.
- Undo stays at the right end in every state.
- Choosing a tool works as today (drawing canvas, placement overlay with Cancel / Place). When that
  finishes, the bar returns to the mode the user was in, not to reading, so signing two places is
  quick.
- Closing a mode (×) returns to the reading bar. The reader always opens in the reading bar.
- Read-only documents (password-protected): the mode buttons are not shown; the existing
  "read-only" notice stays.
- The top bar keeps Search and More. More keeps Share, Print, Save a copy, Revert; "Organize Pages"
  moves out of it (now the Pages button).

Tap count for signing: today 1 (Sign) → now 2 (Fill & Sign, Signature). Accepted as the cost of
approach A; signing a second spot is then 1 tap because the mode stays open.

### 2a. Text markup on Android and desktop (new; the user's decision)

Today only iOS marks up selected text (Highlight in four colours, Underline, Strikethrough, red). On
Android and desktop "Highlight" is a freehand marker with colour swatches. In this release:

- Android and desktop get the iOS behaviour: in Markup, **Highlight▾ (Yellow, Green, Pink, Blue),
  Underline, Strikethrough** apply to text the user selects on the page. Colours and alpha as on iOS.
- Selecting text: desktop uses the pdf.js text layer (normal mouse selection). Android has no text
  layer today (PdfRenderer draws images), so it gets text geometry from PdfBox (`PDFTextStripper`
  text positions) and a selection gesture: with a text tool active, drag across the text to select
  whole words; the selection shows as a tinted overlay, and lifting the finger applies the tool.
- Saving follows each app's model: Android flattens the marks into the page like its other edits
  (highlight drawn with a multiply blend); desktop stamps them like its other edits (it already
  draws highlight with multiply). Undo and Revert work as for every other edit.
- The freehand marker stays available as a pen colour in **Draw** (highlighter-style stroke), so no
  existing ability is lost.
- Pages with no text (scans): selecting finds nothing; a one-line hint says the page has no text to
  mark up and points to Draw.
- Tests: engine tests for text geometry and for each mark (rotated and cropped pages included, as
  the existing overlay tests do), desktop self-test pixel checks for highlight, underline and
  strikethrough on selected text.

**Desktop:** the floating bottom toolbar gets the same three modes and Undo, with the same tools per
mode. The header keeps search, zoom, Save and the ⋯ menu; the header's page-grid button stays as a
second way into Pages (desktop has room). Save stays desktop-only, as today.

**iPad / tablets:** same as phones; the bar has more room so nothing changes in structure.

### 3. Names (same on every platform)

Tabs: Documents (desktop rail: Files), Tools, Settings. Modes: Markup, Fill & Sign, Pages. Tools:
Highlight, Underline, Strikethrough, Text, Draw, Signature, Undo. Tools tab: Scan Document, Create from
Photos (desktop: Create from Images), Merge PDFs, Extract Pages.

### 4. Accessibility

Every mode and tool button has a text label (visible, or the accessibility label on icon-only items).
The bar must still fit at the largest Dynamic Type / font scale on a small phone: labels may wrap
under the icon or shrink to icon + accessibility label at accessibility sizes.

## Constraints

- iOS: SwiftUI, iOS 26, Liquid Glass, no third-party dependencies, XcodeGen. Keep App Review rules.
- Android: Jetpack Compose, Material 3, no new permissions (manifest stays permission-free).
- Desktop: TypeScript, no UI framework; the self-test (`npm run selftest`) drives the interface with
  pointer events and must be updated for the new bar and pass on all three OSes.
- Existing tests keep passing; editing logic (`ReaderModel`, `ReaderState`, `reader.ts` editing) is not
  rewritten beyond adding the text markup in 2a.
- Screenshots in `screenshots/` (iOS iPhone + iPad, Android, desktop), README and website are updated
  to the new screens after the release builds.

## Release

- iOS: a new TestFlight build of **1.0.0** (the App Store version stays under review, untouched).
- Android: **1.0.4** to closed testing (the user's choice), tag `android-1.0.4`.
- Desktop: **1.2.0** (the user's choice), tag `desktop-1.2.0`; first real test of the in-app updater.
- Release notes: `AppStore/whatsnew/en-US.txt`, `android/whatsnew/whatsnew-en-US`, desktop release notes.

## Build plan in short

One implementer agent per platform, each in its own git worktree and branch, working in parallel
(iOS, Android, desktop). Claude reviews each branch, runs its tests (simulator, emulator, self-test),
fixes or sends back problems, merges to `main`, updates screenshots, README and website, then pushes
and releases with the numbers above.

## Left to the builder

Exact symbols per platform, spacing and tile colours (within the brand palette), how the bar animates
between modes, picker sheet layout, and how labels shrink at large text sizes.
