# ADR 0017: Recent files are Direct Share targets that preselect, never append

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** Scorchsoft

## Context

J9 ("capture a snippet from Chrome into my notes file") was two taps
after the sending app's Share for the last-used file and three for any
other recent one: SS Markdown in the chooser, the file in our sheet, then
Append. Android's Direct Share lets an app put sharing shortcuts in the
system share sheet itself, so the file can be the thing tapped in the
chooser.

`ShareActivity` is exported, as every share target must be. Sharing to a
Direct Share target starts it with `ACTION_SEND` and the shortcut's id in
`Intent.EXTRA_SHORTCUT_ID`, but nothing stops any installed app from
sending the same intent with any id it likes. The same shortcuts also
appear in the launcher's long-press menu, where they start the exported
`MainActivity`.

## Options

1. **One tap: the shortcut appends.** Tapping a target in the system
   sheet writes the text straight into the file. Any app that learns or
   guesses an id could then write into the user's files with no
   confirmation.
2. **The shortcut preselects.** Our sheet opens with that file chosen and
   listed first; the user still taps Append. Two taps for any recent file.
3. **No Direct Share.** Keep three taps for anything but the last file.

## Decision

Option 2, **on by default**, with a switch in Settings → Sharing ("Show
recent files in the share sheet").

- **Never append without the user's tap.** A resolved id only sets the
  sheet's target. Cancel, or choosing another row, behaves as before.
- **The id is a hash, never the URI.** `recent:` plus the first 16 hex
  digits of SHA-256 of the document URI, so the URI never reaches the
  platform's shortcut store, the share sheet or the launcher. The hash is
  not a secret and is not relied on as one: it is unsalted, so an app that
  guesses a recent file's URI can compute its id and bring that file to
  the editor or preselect it in our sheet. It learns nothing back and can
  write nothing without the user's tap, so a per-install salt was judged
  not worth its state (review r3 L9, left as this decision).
- **No shortcut call at launch.** The set last published is kept in
  app-private preferences (`PreferencesPublishedRecord`), and the first
  sync runs 5 s after launch, so a start with nothing changed makes no
  `ShortcutManager` call at all; the platform is read back only when that
  record is missing or a call failed. The share sheet refreshes as it
  closes, not as it opens. The emulator's cold-start benchmark launches
  the app dozens of times in a row, and shortcut calls on each launch
  were the suspect in its system_server stalls.
- **A declined push is a failure.** On API 29 the compat library returns
  false while the platform rate-limits shortcut updates; the sync treats
  that like a thrown refusal and reads the published set back next time,
  so a stale share sheet is retried, not trusted (review r3 L7).
- **An incoming id is only mapped back through our own recents**
  (`DirectShareTargets.resolve`): the setting is on, a recent file hashes
  to it, and we hold a persisted write grant on it (on the document or on
  the folder tree it came from). Anything else (unknown, forged, a raw
  URI, a forgotten file, a lost grant) is ignored and the normal sheet or
  launch runs. No URI is ever read from an extra (SEC-2).
- **Which files.** Up to four (or the platform's per-Activity cap, if
  lower) recent files we may write, ranked exactly as the sheet ranks its
  rows: the last target and pinned files first, then newest. Each is a
  long-lived sharing shortcut in the category
  `com.scorchsoft.ssmarkdown.category.APPEND_TARGET`
  (`res/xml/shortcuts.xml`), labelled with the file's name, and "name ·
  location" as its long label, with a document glyph.
- **Limits.** Shortcut updates are rate-limited, so the published set is
  compared with the wanted set and only the difference is pushed or
  removed; nothing is called when nothing changed. The sync runs on the
  I/O dispatcher when the graph starts, whenever the setting, the recent
  list or the last target changes, and each time the share sheet opens
  (which is how a lost grant drops out). A refused call is swallowed and
  retried on the next sync. A successful append reports the shortcut as
  used, so the system ranks it higher.
- **Launcher long-press.** The shortcut's own intent is `MainActivity`
  with `ACTION_OPEN_RECENT` and the id. It resolves the same way and opens
  the file as a recent open does; an unknown id just opens the app.

## Consequences

- Any recent file is two taps after Share (`TapCountAuditTest`), and the
  last-used one stays two.
- Recent file names are visible in Android's share sheet and the
  launcher's long-press menu. That is a privacy cost for a shared or
  shoulder-surfed phone; the setting removes them all at once, and the
  ux-spec and README say so.
- A hostile app can, at most, open our sheet with one of the user's
  recent files chosen, or open that file in the editor. It cannot write.
- How each launcher and each Android version's share sheet shows these
  targets can only be checked on a phone (`docs/edge-cases.md`).
