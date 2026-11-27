# Fill & Sign tools, Split PDF and Password-protect

Date: 2026-11-27. Platforms: iOS/iPadOS, Android, desktop (Electron).

## Why

The Tools redesign (spec `2026-11-26-tools-redesign-design.md`) gave each planned feature a slot.
Asked what was left on the roadmap, Claude suggested these next and the user said to build them.
They are the things people want most often after signing: filling a form, dating and initialling a
page, ticking boxes. Split and Password-protect are the cheapest additions to the Tools tab, and
Password-protect makes the hidden "Protect & optimize" section appear.

## Goals

- Fill & Sign covers a typical paper form end to end: fill its fields, tick, initial, date, sign.
- Split a PDF into several files without changing the original.
- Protect a PDF with a password **in place** (the user's choice) and keep working on it in PDFoundry:
  after the password is entered, every edit is saved with the same password (Claude's pick, which the
  user accepted). The password can be taken off again.
- Same names, places and behaviour on all three apps.

## Non-goals

- Creating new form fields (only filling the fields a PDF already has).
- Editing restrictions (print/copy permissions). Protect sets an open password only.
- Recovering a forgotten password.
- Digital (certificate) signatures. Signature fields are filled with the drawn signature image.
- Compress, Redact, OCR and the other roadmap items.

## Anti-goals (a failure even if it works)

- A protected file silently loses its password after an edit, Undo, Revert, Save As, or a save that
  fails halfway.
- A filled form whose fields can no longer be changed in other apps (Acrobat, Preview), except where
  the user chose a flattening action. Nothing in this release flattens fields.
- Protect replaces the file before the user has typed the same password twice and seen the warning.
- Split or Protect touches a file other than the one the user picked.
- Any pre-permission screen (App Review 5.1.1(iv)). No new permissions on any platform.

## Approaches

One sensible way, the same as the last release: one implementer agent per platform in its own git
worktree, an independent code review per platform, Claude fixes, tests on simulator / emulator /
self-test, merges, then updates screenshots, README and website and releases. Each app already has
the pieces this builds on (its password prompt, the Tools grid, the Fill & Sign bar, the signature
pad and placement overlay), so no new libraries are needed: PDFKit (iOS), PdfBox-Android (Android)
and @cantoo/pdf-lib + pdf.js (desktop) all fill AcroForm fields and write encrypted PDFs.

## Design

### 1. Fill & Sign bar

`×  Signature  Initials  Text  Date  ✓  ✗   ↶`

On a narrow phone the bar scrolls sideways or groups ✓ and ✗ under one "Mark▾" button (builder's
call); every item keeps a text or accessibility label.

- **Initials**: drawn on the same pad as signatures and saved in their own list (separate from
  signatures, same storage style per platform). Placed and resized like a signature.
- **Date**: places today's date as a text stamp, in the device's medium date style for its locale
  (desktop: the system locale). It is placed like Text and can be edited like Text before it is
  placed. *(Claude's call: no date-format setting in this release.)*
- **✓ and ✗**: placed and resized like a signature, drawn in the ink colour used for text (dark),
  as vector or crisp image stamps that stay sharp when zoomed.
- After placing, the bar stays in Fill & Sign, as today.

### 2. Form fields

- Fields are usable **whenever a document is open and editable**, in any mode, as in Acrobat. A light
  tint shows where fields are (platform-standard look).
- Supported: text (single and multi-line), checkbox, radio group, choice (dropdown / list).
  **Signature fields**: tapping one opens the signature list; the chosen signature is stamped to fit
  the field's box (the field itself stays an unsigned field; no certificate).
- Field values are saved **as form field values**, not flattened into the page, on every platform,
  so other apps can still read and change them. Values are written with appearance streams so they
  show in viewers that don't regenerate appearances.
- Saving follows each app's model: phones save after each field change (when the field loses focus
  or a box is ticked); desktop marks the document changed and saves on Save.
- **Undo** steps back field changes too, one step per committed field change.
- Read-only fields, and fields in a read-only document, can't be changed.
- Android has no field layer today (pages are images), so it draws its own field controls over the
  page from PdfBox's field list (position from each widget's rect, handling page rotation and crop
  box like the existing overlay code).
- Desktop uses pdf.js's form layer for display/input and writes values with pdf-lib on Save.
- XFA-only forms: not supported; the page is shown as today with no fields.
- **Non-Latin text (Arabic first) in fields** *(Claude's call)*: the field's value is always saved as
  real text (`/V`), so it stays editable and searchable in other apps. The field's *appearance* is
  built so it displays correctly everywhere: when the field's own font can encode the text (Latin
  with Helvetica, say), a normal text appearance; otherwise the app draws the shaped text itself
  (Android: Android's text layout, as the Text stamp does; desktop: the window's canvas, as its
  stamps do; iOS: PDFKit's own widget drawing) and uses that as the appearance image. Another app
  that edits the field later regenerates it. Never a crash or a blank field. Tests include an Arabic
  value round-trip.
- **Taps: fields vs. tools** *(Claude's call)*: while a placement or drawing tool is active (Text,
  Date, ✓, ✗, Signature, Initials, Draw, text markup), the tool wins, so a ✓ or date can be stamped
  on top of a field (forms with fake fields are common). With no tool active (reading, or a mode
  open with nothing picked), a tap on a field edits the field.

### 3. Split PDF (Tools › Combine & split)

- Picker: one file (as Extract Pages). Then a sheet with three choices: **Every page**, **Every N
  pages** (N entered, default 2), **Custom ranges** (text such as `1-3, 4-6, 9`; invalid text shows
  a one-line error and disables Split). A line shows how many files will be made.
- Never writes the source, never adds the source to the library or recent list (same rule as
  Extract).
- Output names: `<name> (part 1).pdf`, `(part 2)`, … Phones: into Documents (library), with a short
  confirmation; existing names get the usual unique-name suffix. Desktop: asks for a folder; refuses
  to write over the source; if a part's name exists in that folder it asks once (Replace / Keep both
  / Cancel; Keep both adds a number).
- A protected source: asks for its password first (full access needed, as in 5); every part is
  written with the same password. *(Claude's call: a split of a protected file stays protected.)*
- **Extract Pages follows the same rule** *(Claude's call)*: a protected source asks for its password
  and the new file keeps it. **Merge is unchanged**: it still refuses a protected source with today's
  message (a merged file mixing protected and unprotected sources has no obvious password); the
  message now says to use More › Remove Password first.

### 4. Password-protect (Tools › Protect & optimize; also reader More menu)

- Which files *(Claude's call)*: the picker lists the library (including files opened in place).
  "Open from Files" / "Browse…" in this picker **opens the chosen file in place** exactly like
  `+ › Open` (phones: listed with its location; desktop: added to recent files), then protects that
  real file. Never a temporary copy (on Android, not `cache/picked`): protecting a copy would leave
  the user's real file unprotected while they believe it is protected. Hand-overs that can only be
  copies (share sheet) are protected as the library copy they became, as listed.
- Tools: picker (one file) → sheet with Password and Confirm fields and the line "If you forget the
  password, the file can't be opened, and it can't be recovered." The Protect button stays disabled
  until both fields match and are non-empty. Then the file is replaced in place with the protected
  version, and a short confirmation shows.
- Already protected file: the tile's flow says so and offers nothing else (use Change/Remove in the
  reader). *(Claude's call: no "change password" in this release; Remove + Protect does it.)*
- Reader More menu: **Protect with Password** (unprotected documents) / **Remove Password**
  (protected documents that are unlocked for editing; asks to confirm).
- Writing in place follows each app's file rules: phones write through the same path as autosave
  (open-in-place files go back to their real location; library files in Documents); desktop writes
  the open file. Desktop with unsaved edits: Protect asks to Save first (Save and Protect / Cancel).
- Encryption: AES-256 where the library supports it, otherwise the strongest it offers (at least
  AES-128; never RC4 40-bit). The entered password is both the user (open) and owner password, all
  permissions allowed. Builder verifies the result opens with the password in Preview / Acrobat /
  Chrome and is refused without it.
- Revert on phones (open-in-place files) restores the bytes from when the file was opened, which may
  be unprotected: this is expected, and Revert already asks to confirm.

### 5. Editing protected files

- Opening a protected file asks for the password, as today. If the password gives **full access**
  (it is the owner password, or user and owner are the same), the document is editable and every
  save writes it with that same password (as user and owner).
- If it only gives limited access (a separate owner password the user doesn't know), or the file has
  restrictions only and opens without asking, it stays read-only with today's notice.
- The password is kept in memory for the open document only; never written to disk, logs,
  preferences or recent lists.
- Android: the unlocked working copy stays in the app's private cache as today; pushes to the real
  file are always encrypted. Undo snapshots and the Revert original stay in private cache and are
  deleted on close as today. Desktop: undo snapshots are in memory; Save and Save As write encrypted.
- Remove Password writes the file unencrypted (after confirming).

## Constraints

- iOS: SwiftUI, iOS 26, no third-party dependencies, XcodeGen. Android: Compose, Material 3, no new
  permissions. Desktop: TypeScript, no UI framework; self-test must cover the new bar items and pass
  on all three OSes.
- Existing tests keep passing. New tests per platform at least: field fill round-trip (text,
  checkbox, radio, choice) still a field after save; ✓/✗/date/initials stamps survive save and
  reload (rotated page included); split modes and range parsing; protect → reopen needs password;
  edit a protected file → still protected with the same password; Remove Password; source unchanged
  by Split.
- Public docs: no signing details (secret names, key files) in README/Website.

## Release

- iOS: a new TestFlight build of **1.0.0** while version 1.0.0 is in App Review. If Apple has
  approved 1.0.0 by release time, ask the user before using 1.1.0.
- Android: **1.0.5**, tag `android-1.0.5`, closed testing.
- Desktop: **1.3.0**, tag `desktop-1.3.0`.
- Release notes: `AppStore/whatsnew/en-US.txt`, `android/whatsnew/whatsnew-en-US`, desktop release.
- Screenshots, README feature/usage tables and website cards updated.

## Left to the builder

Exact symbols, tile colour for Split and Password-protect (brand palette), how the Fill & Sign bar
fits narrow phones, field tint style, the split sheet layout, default ✓/✗ size, and how Undo is wired
for field changes on each platform.
