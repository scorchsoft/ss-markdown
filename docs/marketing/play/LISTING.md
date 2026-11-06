# Google Play listing: default store listing (en-GB)

Everything for Play Console › Grow › Store presence › Main store listing, in the
console's order. The wording follows `../positioning.md` (pains, pillars and its
claims discipline): no "best", "fastest" or "#1", no speed figures, and "free" and
"no ads" only in the full description, never in the title, short description or
graphics. Full description: 3,409 of 4,000 characters; short description: 78 of 80.

Live listing: <https://play.google.com/store/apps/details?id=com.scorchsoft.ssmarkdown> (package `com.scorchsoft.ssmarkdown`).

## App name (30)

`SS Markdown`

Option, if you want search reach: `SS Markdown: Editor & Viewer` (28). "Viewer"
catches people searching for an "md viewer" to open an attachment.

## Short description (80)

`Edit .md files in place. Quick on big files, offline, and it keeps every edit.`

## Full description (4,000)

```
A calm Markdown editor that opens your own .md files where they live, stays smooth on big files, and keeps your edits safe, even if the app is closed.
Open source and offline, with no ads, no account and no subscription.
Code, Split or Preview in one tap, and type straight into the formatted page, tables included.

BUILT SO YOU DON'T LOSE WORK
• Your edits come back after a crash, or if Android closes the app. Every change is kept in a recovery journal within about a second.
• Saves are read back and checked, and a backup is kept before every overwrite.
• Version history with compare, restore and pinning.
• If a sync app, Drive or a desktop editor changes the file while you're editing, nothing is overwritten. You choose: merge your edits into the new file, compare the two, or keep either version.
• Unsaved is impossible to miss: a red dot, the word "Unsaved" and a red Save button. Autosave if you prefer.

YOUR FILES, IN PLACE
• Open .md and .txt files from Files, Drive, email or any app's share sheet, and save back to the same file. Nothing is copied into the app.
• Folder mode: work across a whole folder, with Save all.
• Share text in from any app; your recent files appear in Android's share sheet.
• Plain Markdown, so leaving is free.

CALM AND QUICK, AT ANY SIZE
• It opens straight to your page. No home screen, no vault, no sign-in.
• Two bars at rest. Copy and Save are always visible; everything else appears only when it applies.
• Big files stay smooth. A 20 MB file scrolls and takes typing without slowing down, built on the same kind of text structure VS Code uses.

EDIT THE WAY YOU THINK
• Code shows the text, Preview shows the page. Switch without losing your place.
• Split shows both, and scrolling either one moves the other.
• Type straight into Preview, tables included, without your hand-written Markdown being reformatted.
• The header shows which section of a long file you're in. Tap the title for an outline, a quick switcher and your drafts.
• Find, with next and previous, in every view.
• Copy the Markdown or the formatted page, all of it or just a selection, ready for email, documents or chat.
• Tidy evens out how the Markdown is written without changing what it says, and asks first.

EVERYTHING MARKDOWN CAN DO
• GitHub-flavoured Markdown, footnotes, alerts and front matter.
• Mermaid diagrams and KaTeX maths, drawn on the phone, even in aeroplane mode. Save a diagram as PNG or SVG.
• Pictures beside your file, with Insert image.
• Export to PDF (A4 or Letter), a single HTML page, or plain text.

MAKE IT YOURS
• Light or dark, or follow your phone.
• Four page themes (Clean, Reader, Compact and Contrast), or make your own with Google Fonts.
• Pinch to zoom; the text reflows.
• Autosave or tap to save, which view a file opens in, a one- or two-row formatting bar, line numbers.

FREE, OPEN SOURCE, PRIVATE
• No ads. No account. No subscription. Nothing locked.
• Works fully offline. The only network use is downloading a Google Font you choose. No analytics.
• Open source under the Apache 2.0 licence: github.com/scorchsoft/ss-markdown

What it isn't: there's no built-in sync (use Syncthing, Drive or Dropbox on your files, and SS Markdown keeps their changes safe), and no tags, backlinks or plugins. It's an editor for your files, not a knowledge base.

SS Markdown is made by Scorchsoft, a software development company in Birmingham, UK.
```

## App icon (512 × 512 PNG)

`app-icon-512.png`: the master logo (`docs/brand/ssmd-logo.svg`), full bleed. Play
rounds the corners itself.

## Feature graphic (1024 × 500)

`feature-graphic.png`, built from `feature-graphic.html`. No price or "free" text (Play
rule). When the listing has a video, Play draws its play button over the middle of this
graphic, so the middle is kept clear.

## Video

Upload `ss-markdown-play-promo` (the 2:00 Play version) to YouTube, then paste its
`https://www.youtube.com/watch?v=…` URL here. It must be **public or unlisted, with
ads (monetisation) off and no age restriction**, or Play won't play it. The YouTube
title, description, thumbnail and settings are in `scorchsoft/video-gen`
`projects/ss-markdown-play-promo/PUBLISHING.md`.

## Phone screenshots (8; 1080 × 1920, 9:16)

Upload `screenshots/01-…png` to `08-…png` in order. Every one is at least 1080 px on
each side, so the listing is eligible for promotion. Built by `screenshot.html` from
`shots.json` (`node render.cjs shots`). App screens are the Robolectric harness's
420 dpi captures (in `src/`); the two Preview pages are the Preview renderer's own
captures of the demo document, shown on their own, not pasted into app chrome.

| # | Caption | Screen |
|---|---|---|
| 1 | Your edits come back | `editor-recovered` (light) |
| 2 | Opens straight to your page | `editor-draft` (light) |
| 3 | See the page as you write | Preview, Clean theme (light) |
| 4 | Changed elsewhere? Nothing is overwritten. | `conflict-sheet` (dark) |
| 5 | Saved back to the same file | `editor-saved` (light) |
| 6 | Diagrams and maths, offline | Preview, Clean theme (dark): route diagram and formula |
| 7 | Find in every view | `find` (light) |
| 8 | Your theme, your fonts | `theme-editor` (dark) |

Not used: `editor-big-file`. The harness file is 15 lines long and would look staged;
re-shoot it on a phone with a genuinely long file before claiming big files in a
screenshot (the description and video already carry that claim).

Render: `PLAYWRIGHT=<folder with node_modules/playwright> node render.cjs feature`
and `… node render.cjs shots`.

## Tablet screenshots (7-inch and 10-inch; 6 at 1440 × 2560, 9:16)

Upload `tablet-screenshots/01-…png` to `06-…png` to both the 7-inch and the 10-inch
slots. Built by `tablet-shot.html` from `tablets.json` (`node render.cjs tablets`).
The screens are the Robolectric harness at 840 × 1200 dp, 320 dpi (1680 × 2400, the
size of the harness's own `width-840-docked` shot), with the folder panel docked. They
were rendered by temporary copies of existing harness tests at that size (recovered,
saved, conflict sheet, navigator, find, theme editor); those copies weren't committed.

| # | Caption | Screen |
|---|---|---|
| 1 | Your edits come back | recovered, folder docked (light) |
| 2 | Work across a whole folder | saved, folder docked, one unsaved file and Save all (light) |
| 3 | Changed elsewhere? Nothing is overwritten. | conflict sheet (dark) |
| 4 | Jump to any heading | navigator outline (light) |
| 5 | Find in every view | find, folder docked (dark) |
| 6 | Your theme, your fonts | theme editor (dark) |

Not used: the harness's 600 and 1280 dp Split shots (their Preview pane is a stand-in,
because Robolectric can't run a WebView) and History (its sample is dated 1970).
