# Security policy

SS Markdown opens files from other apps and renders their Markdown, and any
HTML inside it, in a WebView. A flaw there could let a document run script,
read other files or leak text, so we treat security reports as urgent.

## Reporting a vulnerability

**Please do not open a public issue.** Report it privately through GitHub:
on this repository, open the **Security** tab and choose **Report a
vulnerability** (GitHub's private security advisories). Only the maintainers
can see it.

Please include:

- what an attacker can do, and what they need (a crafted `.md` file, a shared
  text, a malicious font, and so on);
- the smallest file or steps that reproduce it;
- the app version (Settings → About) and your Android and WebView versions.

We aim to acknowledge a report within 5 working days and to agree a fix and a
disclosure date with you. We will credit you in the advisory and the changelog
unless you would rather we did not.

## Supported versions

Until 1.0, only the latest build is supported. Fixes are not back-ported.

## Scope

In scope: the Android app in `apps/android`, including the Preview page in
`feature/preview/web` and its bridge to the app. Especially:

- script execution or escaping the Content Security Policy from a document;
- reading or writing files the user did not choose;
- text leaving the device (the app is offline apart from Google Fonts
  downloads the user asks for);
- loss or corruption of the user's work.

Out of scope: issues that need a rooted or already compromised device, and
bugs in Android or the system WebView itself (please report those upstream).
