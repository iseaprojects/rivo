# Security policy

## Reporting

Report a vulnerability privately through GitHub's **Security advisories** tab on the
repository ("Report a vulnerability"). Please do not open a public issue for it.

Include the app version, the flavor (`fast` or `ytdlp`), the Android version and the smallest
reproduction you have. If the report involves cookies, redact the `SAPISID` and `__Secure-*`
values first.

You can expect an acknowledgement within a few days. This is a volunteer project: there is no
bug bounty and no formal SLA.

## Supported versions

The latest release receives fixes. Older builds are not patched; the in-app check only tells
you when a newer release exists.

## Scope

In scope: cookie storage and origin scoping, the documents provider, intent and deep-link
handling, the extension loader, and anything that would let another app read data Rivo holds.

Out of scope: behaviour inherent to reading public site responses — a site changing its markup
or an extractor breaking is a bug report, not a vulnerability — and requests to add DRM,
age-gate or geo-restriction bypasses.

## What not to paste

Rivo stores an optional cookies jar and the plain-text `config` inside the app's private
storage. Do not paste a cookie export, a PO token or a full `config` into an issue or a pull
request.
