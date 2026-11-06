# SS Markdown positioning: Play listing and promo video

Research done 2026-11-02 for the Google Play listing and a 60–90 s promo
video. It builds on `docs/research-findings.md` (2026-11-01), which has
the Markor issue evidence in more depth.

**Sources and their limits.** The evidence comes from Google Play listing
pages (fetched directly on 2026-11-02; only the 3 "most relevant" reviews
are visible per app), GitHub issues, the Obsidian and Joplin forums,
Lemmy, DEV, Thurrott, Android Authority, Capterra, and Google's own
Play Console guidance. Review aggregators (chrome-stats, AlternativeTo)
and Reddit returned 403 or nothing usable, so **Reddit is not quoted**.
Quotes are verbatim, including the original spelling.

**What we may claim.** Every claim below is something the app does today
(README, CHANGELOG, `how-it-works.md`, `performance-techniques.md`).
Undo that survives the app being killed and Direct Share targets shipped
in 0.2.0 and may be claimed. Copy as formatted text, Find next/previous
in every view, and pictures beside a file (with Insert image) ship in
0.2.2; claim them once that release is out.

---

## 1. Pain map

| # | Pain | What users say (sourced) | Does SS Markdown answer it? |
|---|---|---|---|
| P1 | **Edits lost or silently reverted** | "Markor autosave doesn't work, when my note open and then I switch to another app" ([Markor #1470](https://github.com/gsantner/markor/issues/1470), 14 Oct 2021) · "I write something in a note, and come back after a while to find it not there anymore" ([Obsidian, Google Play review](https://play.google.com/store/apps/details?id=md.obsidian), 5 Aug 2026) · "once in a blue moon, I save changes to an existing [file]…" ([Simple Markdown, Google Play review](https://play.google.com/store/apps/details?id=com.wbrawner.simplemarkdown), 4 Dec 2022) | **Yes, the core promise.** A checksummed recovery journal (edits survive a kill within about a second), saves that are read back and verified, a backup before every overwrite, and version history. The unsaved state is shown three ways. |
| P2 | **Sync conflicts and notes that disappear** | "I was surprised when I found out that Joplin looses my data once in a while" ([Joplin forum](https://discourse.joplinapp.org/t/data-loss-with-joplin-android-app-and-nextcloud/44644), 31 Mar 2025) · "changes in the Android App then overwrite the PC content, without notice or warning" ([Joplin forum, Android conflicts](https://discourse.joplinapp.org/t/android-conflicts/37780)) | **Partly, and honestly.** We have **no sync**. When another app (Syncthing, Drive, a desktop editor) changes an open file, nothing is overwritten: a sheet offers merge, compare, or keeping either side, and the other side stays in History. |
| P3 | **Lag on big files** | "When dealing with large text files, with more than 100 KB and 5000 lines, a noticeable delay occurs" ([Markor #2739](https://github.com/gsantner/markor/issues/2739), 19 Feb 2026) · "Joplin gets un-usably slow on Android as the note gets longer and longer" ([Joplin forum](https://discourse.joplinapp.org/t/editing-a-long-note-is-slow-on-android/4441), 25 Nov 2019) · "the android app keeps crashing in the background when having a very large (70k+ characters) note open" ([Simplenote, Google Play review](https://play.google.com/store/apps/details?id=com.automattic.simplenote), 12 Mar 2024) | **Yes.** A piece-tree text engine (the structure VS Code uses) and an editor that draws only what's on screen. On a CI emulator a 20 MB file opened in 0.34–0.66 s and a keystroke cost 1.4–2.4 ms on average, the same as at 1 MB. **Emulator numbers; phone numbers are still to come**, so say "big files stay smooth", not "fastest". |
| P4 | **Slow to start, so useless for quick notes** | "Launching is so slow that I don't even bother using it for notes on the go" ([Obsidian forum](https://forum.obsidian.md/t/mobile-startup-reduce-the-time-until-the-user-can-write/57450), 4 Sep 2023) · "on mobile, it needs to be very quick because I want to use it dozens of times per day" (same thread, 31 Aug 2023) · a Pixel 9 user measuring "about 4.3 to 9 seconds" ([Obsidian forum](https://forum.obsidian.md/t/obsidian-android-slow-startup-performance-and-misleading-startup-time-breakdown/95793)) | **Yes.** It opens straight to an editor with the cursor in it: no home screen, vault or sign-in. Cold start to an editable document was 0.59–0.88 s on the emulator. Show it rather than quote a number. |
| P5 | **Can't open a .md from Files, or it's locked in a vault** | "I find it strange that a robust Markdown editor like Obsidian doesn't allow opening a `.md` file" ([Obsidian forum](https://forum.obsidian.md/t/have-obsidian-be-the-handler-of-md-files-add-ability-to-use-obsidian-as-a-markdown-editor-on-files-outside-vault-file-association/314?page=9), 16 Mar 2026) · "Obsidian won't open markdown files when I tap on them, nor can I set Obsidian as my default markdown editor" ([Obsidian forum](https://forum.obsidian.md/t/feature-default-app-for-markdown/53473), 31 Jan 2023) · Obsidian "creates 'vaults' for your notes, making them less portable" ([Lemmy](https://lemmy.imagisphe.re/post/2717028)) | **Yes.** It opens `.md` / `.txt` from Files, Drive, email or any share sheet, and saves back **in place**, never as an imported copy. Read-only sources are labelled, with Save as…. Folder mode works on any folder. |
| P6 | **Plain-text editors have no live editing; live editors don't do plain folders** | "there is no live editing" in Markor, and "[I] haven't found a FOSS app that does both well yet" ([Lemmy, Android@lemmy.world](https://lemmy.imagisphe.re/post/2717028)) · "I'd love to see … syntax highlighting" ([Simple Markdown review](https://play.google.com/store/apps/details?id=com.wbrawner.simplemarkdown), 7 Dec 2025) | **Yes, and this gap is distinctive.** Code / Split / Preview with position kept in sync; type straight into the formatted Preview, tables included, with only the block you touched rewritten. Plain files and folder mode stay. |
| P7 | **Subscriptions and paywalls on basics** | "Need to pay $90 per year to unlock more features of the app, which is a ludicrous amount" ([Standard Notes, Google Play review](https://play.google.com/store/apps/details?id=com.standardnotes), 11 Jun 2026) · "the yearly subscription I bought is absolutely useless" and "another paid for feature, and I'd already paid" ([JotterPad, Google Play reviews](https://play.google.com/store/apps/details?id=com.jotterpad.x), 3 May 2022 and 9 Mar 2024) · paid add-on sync from $4 a month ([Obsidian](https://obsidian.md/blog/standard-plan/)) | **Yes.** Free, Apache-2.0, no in-app purchases, no subscription, nothing locked. |
| P8 | **Clutter, big toolbars, ugly UI** | "cluttered interface, fumble through convoluted menus" and "a lot of apps tend to have big toolbar always opened" ([DEV](https://dev.to/erzicky/finally-a-good-looking-markdown-editor-for-android-138g), 22 Jul 2024) · "could do with some settings to turn off features that some users consider superfluous" ([Markor, Google Play review](https://play.google.com/store/apps/details?id=net.gsantner.markor), 3 May 2020) · a 2025 Obsidian forum request for a "Lite Mode" in the mobile app ([forum](https://forum.obsidian.md/t/can-we-have-a-obsidian-lite-mode-in-mobile-app/103942)) | **Yes, by principle.** "Features may be deep, chrome may not be busy": two bars at rest; the formatting strip appears only while typing and is one row. |
| P9 | **Toolbar or Save hidden by the keyboard or on long docs** | "often it remains invisible behind the Gboard keyboard" ([Obsidian forum](https://forum.obsidian.md/t/bug-bottom-button-bar-is-invisible-behind-the-keyboard/55016), 23 Feb 2023) · "with long documents, the header and 3-dot menu hide, so cannot save documents" ([Simple Markdown, Google Play review](https://play.google.com/store/apps/details?id=com.wbrawner.simplemarkdown), 11 Aug 2026) | **Yes.** Copy and Save are always visible; the caret line and strip stay above the keyboard (R8). Needs checking on phones with several keyboards. |
| P10 | **Apps abandoned or not truly offline** | iA Writer for Android "will not be updated along with the other versions of the app" ([Thurrott](https://www.thurrott.com/mobile/android/310882/ia-writer-abandons-android-citing-google-play-policy-changes), 5 Oct 2024) · Notion: "most reviewers find offline access unreliable" ([Capterra summary](https://www.capterra.com/p/186596/Notion/reviews/?rating=1)) · Epsilon Notes' Play page returned "Not found" for GB on 2026-11-02 (its last listed update was May 2021, per [AppFollow](https://apps.appfollow.io/android/epsilon-notes-markdown-editor/com.ekartoyev.enotes?country=es)) | **Yes, as far as anyone can promise.** Fully offline (network only to fetch a Google Font you choose), and open source, so the code outlives any one company. Files are plain Markdown, so leaving is free. |

**Where we say "no":**
- **No sync, by design.** Use Syncthing, Drive or Dropbox on the files; we keep their changes safe (P2).
- **No tags, backlinks, graph or plugins.** This is an editor for files, not a knowledge base like Obsidian.
- **No to-do.txt, encryption or Git.**
- **History is per device** and goes with an uninstall (Export history exists). Your files are never at risk.

## 2. What users love in the category (keep it)

- **Plain files you own, edited where they live.** It's Markor's top praise: "Each note is a separate [file]…" ([Play review](https://play.google.com/store/apps/details?id=net.gsantner.markor), 28 Mar 2025). Obsidian leads its listing with "a local folder of plain text Markdown files".
- **Simple, no gimmicks, no ads.** "Simple, no gimmicks, no ads, works" and "clutter free, simple and performant" (Simple Markdown reviews, Aug 2026 and Dec 2025).
- **Offline first.** Markor's second listing line is "Work completely offline".
- **Open source as trust.** Joplin opens its listing with "Privacy-first… open-source code"; FOSS users drop Obsidian over it ([Lemmy](https://lemmy.imagisphe.re/post/2717028)).
- **Works with Syncthing and desktop editors.** People pair Obsidian desktop with a lighter Android editor on the same files.
- **Mermaid, maths, tables on mobile.** Epsilon Notes was praised for tables, GFM and Mermaid.

## 3. Positioning

**Positioning line**

> SS Markdown is the Markdown editor for Android that opens your own
> `.md` files where they live, stays smooth on big ones, and doesn't lose
> your work. It's free and open source, works offline, and has no ads
> and no account.

Tagline for the end card and feature graphic: **Your Markdown files. Calm, quick, and kept safe.**

**Three pillars**

1. **Never lose work.** Recovery after a crash or kill, verified saves,
   version history, and nothing overwritten when another app changes the
   file. (P1, P2)
2. **Your files, in place.** Open from Files, Drive or any app and save
   back to the same file. Plain Markdown, folder mode, offline, no account,
   open source. (P5, P7, P10)
3. **Calm and quick, at any size.** Opens straight to the page with two
   bars and nothing else. Code, Split or Preview in one tap, with typing in
   the formatted view. Big files stay smooth (built on the text structure
   VS Code uses). (P3, P4, P6, P8)

**Differentiators that are genuinely ours**

| vs | Their strength (keep in mind) | What we do that they don't |
|---|---|---|
| **Markor** (free, FOSS, plain files, 4.7★) | Plain files, offline, to-do.txt, mature | Never-lose-work layers and the conflict merge; type into Preview incl. tables; big-file engine (Markor lags from ~100 KB, #2739); calmer chrome |
| **Obsidian** (free app, paid Sync; closed source) | Knowledge base, plugins, graph, sync | Opens any `.md` from Files with no vault; opens straight to an editor; open source; no paid tier |
| **Joplin** (FOSS, paid Cloud optional) | Sync and E2E encryption, attachments | Plain `.md` files in place (Joplin keeps notes in its own database); no sync conflicts to lose notes in; big-file editing |
| **Simple Markdown** (FOSS, tips) | Very simple, no ads | Syntax highlighting, Split, tap-to-edit Preview, Mermaid/KaTeX, recovery and history |
| **JotterPad / Standard Notes** (subscriptions) | Writing tools; encryption and sync | Nothing locked behind a subscription |
| **Notion / Google Keep** (contrast) | Cloud, collaboration, quick capture | Real Markdown files, works fully offline, no account. Keep has no Markdown editing ([Android Authority](https://www.androidauthority.com/google-keep-markdown-export-apk-teardown-3666449/)) |
| **iA Writer / Epsilon Notes** | Loved writing feel; Mermaid and tables | Actively built, open source |

**Claims discipline**
- "Fastest", "best" and "#1" are out (Google forbids ranking claims in
  listings, and our numbers are emulator-only).
- Use "big files stay smooth" and "typing doesn't slow down as the file
  grows". "Built on the text structure VS Code uses" is subtext only.
- APK size: say "**about 6 MB**". The measured hand-over is 6.15 MB
  (`apps/android/CLAUDE.md`, 2026-11-01); the brief's 6.6 MB doesn't match
  anything in the repo, so check the release build before printing a figure.
- "Never lose work" is a design goal; the README disclaimer still applies.
  In the listing, say "built so you don't lose work" or describe the
  mechanism ("your edits come back after a crash").

## 4. Play listing draft

**Google's rules that shape this** ([Play Console best practice](https://support.google.com/googleplay/android-developer/answer/13393723)):
the title is ≤ 30 characters with no "Free", "No Ads", "#1", emoji or
pricing. The short description is ≤ 80 characters with no "free" or
"best". Screenshots carry minimal text and no pricing or award text.
"No ads" and "free" may appear in the full description body only.

**Title (28):** `SS Markdown: Editor & Viewer`
Alternative (28): `SS Markdown: Markdown Editor`. "Viewer" catches people who search for "md viewer" to open an attachment.

**Short description (78):**
`Edit .md files in place. Quick on big files, offline, and it keeps every edit.`
Alternative (80): `Open, edit and save your .md files in place. Offline, quick, and it keeps edits.`

**Full description, first three lines** (the part visible before "More"):

> A calm Markdown editor that opens your own .md files where they live, stays smooth on big files, and keeps your edits safe, even if the app is closed.
> Open source and offline, with no ads, no account and no subscription.
> Code, Split or Preview in one tap, and type straight into the formatted page, tables included.

Then the feature list. It may reuse the README "Why" bullets. Add Mermaid, KaTeX, GFM, folder mode, share-in, themes, Google Fonts and PDF export, and end with the GitHub link.

**Screenshot set** (phone, light and dark; captions ≤ 6 words where possible)

| # | Caption | Screen |
|---|---|---|
| 1 | Opens straight to the page | `editor-unsaved.png` (red dot, "Unsaved", red Save) |
| 2 | Your edits come back after a crash | `editor-recovered.png` |
| 3 | Code, Split, Preview. Same paragraph | `editor-split.png` |
| 4 | Diagrams and maths, offline | `docs/screenshots/preview-dark.png` (Reader theme, formula and Mermaid) |
| 5 | Changed elsewhere? Nothing is overwritten | `conflict-sheet.png` |
| 6 | Big files stay smooth | `editor-big-file.png`, **re-shot** with a genuinely long file (the harness shot shows 15 lines and could look staged) |
| 7 | Jump to any heading | `navigator.png` |
| 8 | Every version, restorable | `history.png` |
| 9 | Your theme, your font | `theme-editor.png` or `font-picker.png` |
| 10 (tablet) | A whole folder, Save all | `docs/screenshots/tablet-*.png` / `width-840-docked.png` |

Gaps to capture on a device: a .md opened from the Files app (in-place
save), and tap-to-edit on a table in Preview. The Robolectric harness
can't render either, because it can't run a WebView or another app.

## 5. Promo video brief (≈ 120 s, 16:9, works muted)

**Norms.** A Play promo video is a YouTube link, landscape, 30 s – 2 min.
Turn monetisation off, or it stops playing in the listing. Viewers can
drop out any second, about 10% every 5 s, so the app appears within 5 s.
Many watch muted, so on-screen text carries the story
([Apptamin](https://www.apptamin.com/promo-video/guide-promo-videos-play-store/)).
Show only Android devices. A feature graphic (1024 × 500) is required and
sits under the play button.

**Look.** Show a centred phone frame on a neutral background in the
app's own off-white or ink. Large Inter captions sit beside the phone,
with one line at a time and a maximum of 8 words per card. Use the
Scorchsoft red only where the app uses it: unsaved, Save. Use a quiet
music bed with no beat drops. Shoot about half the beats in light mode and half in
dark, so both are seen before the Light or dark beat names them. Real screen recordings are best; the PNGs
named below are stand-ins for the storyboard and the fallback.

| Time | Beat (pain → answer) | On-screen text | Visual (one-sentence direction) | Optional VO |
|---|---|---|---|---|
| 0:00–0:03 | **Hook** (P1) | `Typed it. App closed. Gone?` | A thumb types a line in a note, then the app is swiped away in Recents. Cut on the swipe. | "You've lost edits to a notes app before." |
| 0:03–0:12 | **Never lose work** (P1) | `Not here. Your edits come back.` → `Every edit is kept within a second.` | Reopen SS Markdown and land on `editor-recovered` with the blue "Your unsaved edits are back" banner, then tap Got it. | "SS Markdown keeps every edit, even if Android closes the app." |
| 0:12–0:21 | **Opens to the page** (P4, P8) | `Opens straight to a blank page.` → `No home screen. No sign-in.` | Cold-launch from the home screen into an untitled doc with the cursor blinking, then type a heading and watch the red dot and "Unsaved" appear (`editor-unsaved`). | "It opens straight to a page, so a quick note is quick." |
| 0:21–0:31 | **Your files, in place** (P5) | `Open any .md from Files or Drive.` → `Save it back where it lives.` | In the Files app, tap `notes.md`; it opens in Preview, a word is fixed and Save is tapped, and the header turns to "Saved" (`editor-saved`). | "Tap a file anywhere. It's edited and saved in place, not copied into a vault." |
| 0:31–0:41 | **Code, Split, Preview** (P6) | `Code, Split or Preview. One tap.` → `Type into the page. Tables too.` | Tap Split (`editor-split`) and then Preview, tap a table cell in Preview, type, and step to the next cell. | "Switch views without losing your place, and edit the formatted page directly." |
| 0:41–0:47 | **Split stays in step** (P6; see "Why these beats") | `Scroll either side. The other follows.` | In Split, flick the Preview pane down past a table and a diagram: the Code pane tracks it to the same block; then drag the Code pane back up and Preview follows. Hold on a frame where both show the same heading. | "Scroll either pane and the other keeps your place." |
| 0:47–0:53 | **Where am I?** (love list) | `Scroll a long file. Always know where.` | Scroll a long document in Preview: the section name under the title changes as each heading passes ("› Chapters", then "› 3 · Carry the whole outcome"), and the thin line under the header fills as you go. Tap the title to open the Navigator's Outline and jump to a section. | "Scroll a long file and the header shows which section you're in, and how far through you are." |
| 0:53–1:02 | **Copy two ways, all or part** (see "Why these beats") | `Copy as Markdown, or as formatted text.` → `The whole page, or just what you select.` | Tap Copy: the chooser shows "Copy Markdown" and "Copy formatted text". Pick formatted, switch to Gmail and paste: the heading, bold, list and table arrive formatted. Back in Preview, long-press to select one paragraph and its list: the selection menu offers Copy (the Markdown) and Copy formatted text; pick Copy and paste into a chat to show the `**` and `-` arrive. (Long-press Copy repeats the last choice, so it is one press next time.) | "Copy the Markdown or the formatted page, all of it or just a selection, ready for email, docs or chat." |
| 1:02–1:11 | **Big files** (P3) | `A 20 MB log. Still smooth.` → small: `Built on the text structure VS Code uses.` | Open a genuinely large log (the "Big-file mode" chip shows), fling to the middle, and type: the characters land without a hitch (`editor-big-file`, re-shot). | "Big files stay smooth. Typing doesn't slow down as the file grows." |
| 1:11–1:19 | **Changed elsewhere** (P2) | `Changed in another app?` → `Nothing is overwritten. Merge it.` | The amber banner "roadmap.md changed in another app" slides in, and Review opens `conflict-sheet` with "Merge my edits into the new file" tapped. | "If Syncthing or Drive changes the file, you choose what happens." |
| 1:19–1:24 | **Light or dark** (love list) | `Light or dark. Or follow the phone.` | On one document in Preview, open ⋮ → View and tap Appearance: Light, then Dark. The whole app turns at once (header, strip, page, code blocks and diagrams), then cut to the same page in Code, dark. | "Light or dark, or let it follow your phone." |
| 1:24–1:30 | **Depth without clutter** (love list) | `Diagrams and maths, offline.` | A quick two-shot montage: `preview-dark` (formula and Mermaid), then `mermaid-sheet`. | "Mermaid diagrams and maths, all offline." |
| 1:30–1:37 | **Page themes** (love list) | `Clean, Reader, Compact, Contrast. Or your own.` | On one document, open ⋮ → View and step through the four built-in page themes (Clean, Reader, Compact, Contrast), then open Settings → Custom themes (`theme-editor`) and change the Preview font. | "Pick a page theme, or make your own with your fonts." |
| 1:37–1:45 | **Settings that fit how you work** (love list) | `Autosave or tap to save.` → `Open in Code, Preview, or where you left off.` | Scroll Settings slowly, landing on four rows in turn: Saving (Autosave / When I tap Save), When a file opens, show (Code / Preview / Last used), Scroll panes together, and Formatting bar (One row / Two rows). Then Version history on, and Show line numbers. | "Autosave or tap to save, how files open, the toolbar, line numbers: it fits how you work." |
| 1:45–1:53 | **Values** (P7, P10) | `No ads. No account. No subscription.` → `Works offline. Open source. About 6 MB.` | Toggle aeroplane mode on and keep typing, then show the `about` screen with its open-source licences. | "No ads, no account, no subscription. Open source, and it works offline." |
| 1:53–2:00 | **End card** | `SS Markdown` / `Free · Open source · No ads` / `github.com/scorchsoft/ss-markdown` / Google Play badge (links to the listing, `play.google.com/store/apps/details?id=com.scorchsoft.ssmarkdown`) | Logo (`docs/brand/logo-240.png`) on off-white, with the line `Your Markdown files. Calm, quick, and kept safe.` | "SS Markdown, by Scorchsoft." |

**Why these beats** (research 2026-11-04; claim "not every editor does this", never "no other app"):
- *Synced Split.* None of the Android editors we checked keeps a source and preview pane in step both ways. Markor and Joplin have no split view; you toggle and lose your place. Users have asked Markor for "scroll synchronization between markdown preview in view-mode and its source code in edit-mode" since 2019 ([#2373](https://github.com/gsantner/markor/issues/2373), 27 Jul 2024, and [#2820](https://github.com/gsantner/markor/issues/2820), 9 May 2026, both open). Joplin reverted its tablet dual pane ([forum](https://discourse.joplinapp.org/t/joplin-mobile-tablet-layout/25775?page=3), 14 Dec 2024). Epsilon Notes syncs "as percentage of the scrolling", which drifts on images ([dev, 2019](https://groups.google.com/g/epsilon-notes/c/bV7dy5r-_Pg)). Obsidian's sync-scroll plugin is desktop-only. Obsidian (Live Preview) and JotterPad (WYSIWYG) avoid the problem by design, so don't call them broken.
- *Formatted copy.* No editor we checked has a built-in "copy as formatted text" on Android. SS Markdown offers both formats for the whole document and for a selection, in Code and in Preview. Obsidian users ask for it and use plugins ([forum](https://forum.obsidian.md/t/copy-and-paste-rendered-version-no-markdown-from-the-editor/74252)). Joplin copies Markdown or plain text ([forum](https://discourse.joplinapp.org/t/a-way-to-copy-a-snippet-of-text-as-html/20359)). Markor's Share → HTML is the nearest thing, so call that "partial".
- Evidence for Simple Markdown, iA Writer (discontinued on Android) and Epsilon's copy is thin: leave them out of any comparison.

Notes for the editor:
- The video's first frame becomes the YouTube thumbnail if none is set. Set
  a custom one that reads clearly without the play button covering it.
- Hold each caption for at least 1.5 s per 4 words; muted viewers read slower than VO.
- The cut runs to the 2-minute limit. If it runs long, drop Settings first, then Page themes: the earlier beats carry the pains.
- Keep "Free · No ads" on the end card and **out of the feature graphic**,
  which is a listing asset under the no-pricing-text rule.
- Undo across a kill and Direct Share shipped in 0.2.0; the v006 film shows both.
- The hook dramatises a real, common pain (P1). It doesn't name or show any
  competitor.
