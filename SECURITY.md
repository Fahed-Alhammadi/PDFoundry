# Security Policy

PDFoundry handles people's documents and signatures, so security reports get priority. Thank you for helping keep
PDFoundry and its users safe.

## Supported versions

Only the latest release of each app gets security fixes. Please update before you report, and check that the problem
still exists there.

| App | Supported | Where to get it |
|---|---|---|
| iPhone and iPad | Latest release | App Store / TestFlight |
| Android | Latest release | Google Play |

## Reporting a vulnerability

**Please don't open a public issue for security problems.**

Report it privately through GitHub:
[**Report a vulnerability**](https://github.com/Fahed-Alhammadi/PDFoundry/security/advisories/new)
(Security tab → *Report a vulnerability*). Only the maintainer can see it.

Please include:

- the app (iPhone, iPad or Android) and its version (Settings → About PDFoundry);
- the device and operating system version;
- what an attacker can do, and what they need for it (a crafted PDF, another app on the device, physical access);
- steps to reproduce;
- a sample file or proof-of-concept code, if you have one.

Please **don't** include real personal documents or signatures. Use test files.

## What happens next

- You get an answer within a few days, saying whether the report is confirmed.
- Confirmed issues are fixed in a new release of the affected app, usually together with a GitHub security advisory.
- With your permission, you're credited in the advisory.
- Please keep the details private until a fixed release is out.

## Scope

In scope:

- the iPhone, iPad and Android apps: opening, displaying, editing, merging, signing and saving PDFs;
- how the apps store documents and signatures on the device;
- how the apps receive files from other apps (share menu, "Open with") and hand files to them (share, print);
- the release workflows in this repository.

Out of scope:

- problems that need a jailbroken or rooted device, or an unlocked device in the attacker's hands;
- bugs in the operating system's own PDF, camera or scanner components (PDFKit, PdfRenderer, VisionKit, the Google
  Play services document scanner); please report those to Apple or Google;
- password-protected PDFs opening read-only: this is intended, so their protection is never removed;
- reports from automated scanners without a working example.

## How PDFoundry protects your documents

- **Nothing leaves the device.** The apps have no account, no server, no analytics and no advertising. The Android
  app does not have the internet permission.
- **Documents and signatures are stored on the device**, protected by the device's own file encryption. A document
  leaves the app only when you share or print it.
- **Minimal permissions.** On iPhone and iPad, the camera, asked only when you tap Scan Document. On Android, none.
- **Keep the app and your device updated.**
