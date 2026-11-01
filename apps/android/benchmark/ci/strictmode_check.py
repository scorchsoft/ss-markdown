#!/usr/bin/env python3
"""Fails on StrictMode violations caused by SS Markdown's own code.

Usage: strictmode_check.py LOGCAT_FILE [--summary FILE]

LOGCAT_FILE is `adb logcat -d -v threadtime` from a run of the DEBUGGABLE
app, which logs every violation with its stack (SSMarkdownApplication
enables StrictMode with penaltyLog in debuggable builds).

A violation is the app's when its stack holds a frame in
`com.scorchsoft.ssmarkdown.` that is the nearest app frame to the
violating call AND no allowlisted library sits between the two. The
allowlist below is the WebView/Chromium internals no app can change: a
WebView's own start-up reads its files on the calling thread, and
Chromium keeps closeables it never closes. Every entry says why.

Violations with no app frame at all (framework or library threads) are
counted and shown, but do not fail the run.
"""

import argparse
import re
import sys

APP_FRAME = "com.scorchsoft.ssmarkdown."

# Library code between the violating call and our frame that we cannot
# change. Matching is on the frame's class prefix.
ALLOWLIST = {
    "org.chromium.": "Chromium (WebView's engine) reads its own files on the calling thread at start-up",
    "com.android.webview.": "the WebView provider's loader",
    "android.webkit.": "WebView's framework glue: WebView construction, cookie and data-dir setup",
    "com.android.org.chromium.": "WebView's renamed Chromium classes on some images",
}

LINE = re.compile(r"^\S+\s+\S+\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+StrictMode\s*:\s?(.*)$")


def parse(path):
    """Yields (header, frames) per violation."""
    current = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            m = LINE.match(raw.rstrip("\n"))
            if not m:
                continue
            pid, msg = m.group(1), m.group(3)
            if "StrictMode policy violation" in msg:
                if current:
                    yield current
                current = {"pid": pid, "header": msg.strip(), "frames": []}
            elif current and current["pid"] == pid:
                frame = msg.strip()
                if frame.startswith("at "):
                    current["frames"].append(frame[3:])
                elif frame.startswith("Caused by") or frame.startswith("..."):
                    current["frames"].append(frame)
    if current:
        yield current


def classify(violation):
    """'app', 'allowlisted: <why>' or 'outside'."""
    frames = [f for f in violation["frames"] if not f.startswith("Caused by") and not f.startswith("...")]
    for i, frame in enumerate(frames):
        if frame.startswith(APP_FRAME):
            between = frames[:i]
            for prefix, why in ALLOWLIST.items():
                if any(f.startswith(prefix) for f in between):
                    return "allowlisted: " + why
            return "app"
    return "outside"


def kind(header):
    m = re.search(r"android\.os\.strictmode\.(\w+)", header)
    return m.group(1) if m else header[:80]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("logcat")
    parser.add_argument("--summary")
    args = parser.parse_args()

    tally = {"app": [], "allowlisted": [], "outside": []}
    for v in parse(args.logcat):
        verdict = classify(v)
        tally[verdict.split(":")[0]].append((v, verdict))

    lines = ["### StrictMode (monkey run, debuggable build)", ""]
    lines.append(
        f"App code: **{len(tally['app'])}** · allowlisted WebView internals: {len(tally['allowlisted'])}"
        f" · outside the app's code: {len(tally['outside'])}"
    )
    seen = set()
    for group in ("app", "allowlisted", "outside"):
        for v, verdict in tally[group]:
            first_app = next((f for f in v["frames"] if f.startswith(APP_FRAME)), "-")
            key = (group, kind(v["header"]), first_app)
            if key in seen:
                continue
            seen.add(key)
            top = v["frames"][0] if v["frames"] else "-"
            lines.append(f"- `{group}` {kind(v['header'])} at `{first_app}` (top `{top}`) {verdict if group != 'app' else ''}")
    report = "\n".join(lines) + "\n"
    print(report)
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as f:
            f.write(report)
    for v, _ in tally["app"]:
        print("::error::StrictMode violation in app code: " + v["header"])
        for frame in v["frames"][:25]:
            print("    at " + frame)
    return 1 if tally["app"] else 0


if __name__ == "__main__":
    sys.exit(main())
