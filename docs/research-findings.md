# What people hate (and love) about Markdown editors

Research done 2026-11-01 for iteration 0. About 30 sources were used.
The strongest are the gsantner/markor GitHub issues ranked by comment
count, and the Obsidian, Joplin and Inkdrop forums. F-Droid, Reddit and
Lemmy threads, and iA's support pages, back them up.

**Caveat:** Google Play review text couldn't be retrieved; the store page
was truncated and aggregators were blocked. So app-store evidence is
second-hand.

Each finding is judged against our vision (narrow, unbloated, built for
Andrew first). Requirements we adopted are marked **→ Rn** and listed at
the end.

## Paper cuts, ranked by frequency

| # | Complaint | Evidence | Verdict |
|---|---|---|---|
| 1 | **Edits lost or reverted** after switching apps, closing, or "reload"; no feedback that a save happened | Markor [#1470](https://github.com/gsantner/markor/issues/1470) (41 comments), [#1671](https://github.com/gsantner/markor/issues/1671), [#708](https://github.com/gsantner/markor/issues/708), [#2515](https://github.com/gsantner/markor/issues/2515); [F-Droid forum](https://forum.f-droid.org/t/how-to-save-a-note-in-markor-solved/4097) | Covered by the journal, verified saves, the unsaved state and the save toast. Reload is now a history checkpoint (→ R4). |
| 2 | **Lag or crashes on large files**: from 100 KB / 5,000 lines in Markor; also Obsidian, Joplin, Inkdrop | Markor [#2739](https://github.com/gsantner/markor/issues/2739), [#1743](https://github.com/gsantner/markor/issues/1743), [#2814](https://github.com/gsantner/markor/issues/2814), [#2469](https://github.com/gsantner/markor/issues/2469), [#2500](https://github.com/gsantner/markor/issues/2500); [Obsidian](https://forum.obsidian.md/t/tracking-down-typing-lag-on-mobile/111191); [Joplin](https://discourse.joplinapp.org/t/editing-a-long-note-is-slow-on-android/4441); [Inkdrop](https://forum.inkdrop.app/t/typing-in-large-notes-on-android-is-very-slow/1138/2) | Covered by the size tiers, the editor-engine gate and the rubric budget. Notably, Markor's EditText-plus-spans setup is exactly ours, which is why the gate exists (→ R1). |
| 3 | **Cursor jumps, wrong opening position, scroll lost** | Markor [#1790](https://github.com/gsantner/markor/issues/1790), [#2505](https://github.com/gsantner/markor/issues/2505), [#1025](https://github.com/gsantner/markor/issues/1025); [Joplin](https://discourse.joplinapp.org/t/cursor-jumps-to-character-before-position-on-android-v3-0-3-release-from-github/38325); [Obsidian with the Samsung keyboard](https://forum.obsidian.md/t/bug-samsung-keyboard-input-problems/33082) | Position sync is covered. Adopted: restore position for each file across launches (→ R7) and test with several keyboards (→ R6). |
| 4 | **Keyboard clashes**: autocorrect landing text in the wrong place after toolbar actions; toolbar or caret hidden behind the keyboard; Android 15 inset bugs | Markor [#1212](https://github.com/gsantner/markor/issues/1212), [#1859](https://github.com/gsantner/markor/issues/1859), [#2387](https://github.com/gsantner/markor/issues/2387); Obsidian [toolbar behind keyboard](https://forum.obsidian.md/t/bug-bottom-button-bar-is-invisible-behind-the-keyboard/55016), [toolbar too big](https://forum.obsidian.md/t/toolbar-on-android-mobile-too-big/103640) | Gap → R6, R8. |
| 5 | **Can't open from a file manager or share sheet**, files imported as a copy, permission errors | Obsidian [default app](https://forum.obsidian.md/t/feature-default-app-for-markdown/53473), [file association](https://forum.obsidian.md/t/have-obsidian-be-the-handler-of-md-files-add-ability-to-use-obsidian-as-a-markdown-editor-on-files-outside-vault-file-association/314?page=9); Markor [#1886](https://github.com/gsantner/markor/issues/1886) (48 comments), [#197](https://github.com/gsantner/markor/issues/197) (56 comments) | Gap → R2, R3. Small apps advertise "open in place" as their main feature. |
| 6 | Another app's change silently overwritten | Markor [#587](https://github.com/gsantner/markor/issues/587) | Covered (conflict banner; autosave pauses). |
| 7 | Undo inconsistent or lost when switching files | Markor [#894](https://github.com/gsantner/markor/issues/894), [#2515](https://github.com/gsantner/markor/issues/2515) | Gap → R5. |
| 8 | Preview changes what you copy (smart quotes inside code break scripts) | Markor [#2819](https://github.com/gsantner/markor/issues/2819), [#2800](https://github.com/gsantner/markor/issues/2800) | Gap, cheap to fix → R9. |
| 9 | Relative image paths (`../img.png`) don't render | Markor [#2318](https://github.com/gsantner/markor/issues/2318), [#2234](https://github.com/gsantner/markor/issues/2234) | Adopted for folder mode → R10. |
| 10 | Encoding, line endings, trailing whitespace altered | Markor [#1274](https://github.com/gsantner/markor/issues/1274), [#2304](https://github.com/gsantner/markor/issues/2304) | Covered (byte-faithful files). |
| 11 | Theme contrast regressions (grey on grey) | Markor [#1831](https://github.com/gsantner/markor/issues/1831), [#2782](https://github.com/gsantner/markor/issues/2782) | Covered for built-in themes; custom themes → R11. |
| 12 | PDF quality | Markor [#2794](https://github.com/gsantner/markor/issues/2794); thin evidence | Print-to-PDF with an A4 stylesheet covers it. PDF settings are out of scope. |
| 13 | Lock-in, abandonment, subscriptions; files deleted on uninstall | [iA Writer leaving Android](https://www.thurrott.com/mobile/android/310882/ia-writer-abandons-android-citing-google-play-policy-changes), [iA FAQ](https://ia.net/writer/support/help/faq/faq-android), [AlternativeTo](https://alternativeto.net/software/epsilon-notes/about) | Validates plain files, no account and no sync. Untitled drafts stay exportable. |

**Out of scope even though people ask for them:** sync and git, todo.txt,
encryption, a built-in spell checker (the keyboard already does this),
and plugins.

## What people love that fits a narrow app

- Plain files on disk that work with Syncthing; offline; no account
  ([Markor on F-Droid](https://f-droid.org/packages/net.gsantner.markor/)).
  This is already our stance.
- Opening any `.md` from a file manager or email and saving back in place
  ([MarkReader](https://f-droid.org/ja/packages/com.markreader/)) → R2.
- Mermaid and KaTeX on mobile. Planned.
- **One-tap checkbox toggling in Preview**
  ([Markor #497](https://github.com/gsantner/markor/issues/497)) → R12.
- A horizontally scrolling key row with brackets, backticks and cursor
  nudges ([Drafts](https://www.macstories.net/reviews/drafts-4-review/),
  [1Writer](https://apps.apple.com/app/id680469088)). Our formatting
  strip already scrolls; adding symbol keys is R13 (CONSIDER).
- Code blocks that scroll horizontally rather than wrap
  ([Obsidian](https://forum.obsidian.md/t/live-preview-better-treatment-of-code-blocks-code-wrap-horizontal-scroll-and-smart-indent/33718)).
  Covered by the wrap modes.

## Requirements adopted from this research

| ID | Level | Requirement |
|---|---|---|
| R1 | MUST | Keystroke latency < 16 ms on a 500 KB / 10,000-line file. A 5 MB file, or a single 1 MB line, opens without crashing (see `robustness.md` size tiers). |
| R2 | MUST | Register for VIEW, EDIT and SEND on `.md`, `.markdown`, `.mdx`, `.txt`, `text/markdown` and `text/plain`. Edit content URIs **in place** and save back when the URI can be written. Otherwise show a visible **Read-only** badge with "Save as…". Never silently import a copy. |
| R3 | MUST | A lost permission or vanished provider shows a clear, recoverable message, never a blank editor. Unsaved text is always in the journal. |
| R4 | MUST | Every save confirms visibly. Reload from disk, and "take theirs" in a conflict, first records a history checkpoint, so they can be undone. |
| R5 | SHOULD | Each open file has its own undo/redo stack, with a generous limit. It survives switching files, switching views and Preview edits, which are part of the same history. |
| R6 | MUST | Toolbar actions and Preview edits commit or respect the keyboard's in-progress word. Tested with Gboard, Samsung Keyboard and HeliBoard. |
| R7 | SHOULD | Cursor, selection and scroll (Code and Preview) restore for each file when it is reopened, even after a restart. |
| R8 | SHOULD | The caret line and formatting strip always stay above the keyboard. Edge-to-edge insets are correct on Android 15+. The strip is never taller than one row. |
| R9 | SHOULD | Preview never applies smart typography inside code. A code block's copy button and Copy all give the exact source. |
| R10 | SHOULD | In folder mode, relative image paths (including `../`) resolve within the opened tree. |
| R11 | SHOULD | The theme editor warns when a custom theme's text, link or code contrast falls below WCAG AA. |
| R12 | SHOULD | Tapping a task checkbox in Preview toggles it in the source, changing one character and nothing else. |
| R13 | CONSIDER | Optional symbol keys (` [ ] ( ) # * > | ` and ← →) at the end of the formatting strip. Reorderable later if wanted. |
