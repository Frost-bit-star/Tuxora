# Security Policy

Tuxora bundles a full Termux environment and desktop stack, so security matters here.
For issues in **upstream Termux itself**, see https://termux.dev/security. For issues in
**Tuxora** (this fork — the app, the bootstrap build, the bundled desktop and its
configs), please use the process below.

## Reporting a vulnerability

**Please do not open a public issue for a security vulnerability.**

Report it privately via GitHub's **"Report a vulnerability"** button under this repo's
**Security** tab (Security advisories → Report a vulnerability). That keeps the details
private until a fix is available.

Please include, where you can:

- what the issue is and where (file / component / version),
- how to reproduce it,
- the impact (what an attacker could do),
- any suggested fix.

## Scope — examples of what's in scope

- APK signing and update integrity.
- Integrity of the downloaded/bundled bootstrap and desktop files.
- The local login / password gate (`DesktopActivity`, `Crypt`, `$PREFIX/etc/shadow`).
- Handling of secrets in bundled configs (e.g. `~/.config/tuxora/email.conf`).
- Exported app components and intent handling.

## Out of scope

- Vulnerabilities in third-party packages shipped by upstream Termux (report those to
  the relevant project / Termux).
- Attacks that require the device to already be rooted or physically compromised.

## Disclosure

We aim to acknowledge a report within a few days and to coordinate a fix and disclosure
timeline with the reporter. Thank you for helping keep Tuxora users safe.
