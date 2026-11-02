# ADR 0008: The text model is a piece tree ported from VS Code

- **Status:** Accepted
- **Date:** 2026-11-01 (recording a decision made in iteration 1)
- **Deciders:** Andrew (Scorchsoft)

## Context

Big files must feel like small ones (`robustness.md` Part 2): a file's size may
decide how much memory we need, never how much work a keystroke costs. Undo,
the journal, history diffs, syntax colouring and Preview all need to read the
text, several of them off the main thread. ADR 0001 decides the *view*; this
records the *model* underneath it.

## Options

1. **One `String` / `StringBuilder` or a platform `Editable`.** Every edit in
   the middle moves megabytes; every background reader needs a copy.
2. **Gap buffer** (Emacs). Cheap local edits, but snapshots for background
   readers still copy, and line lookup needs a separate index.
3. **Rope.** Balanced, but every edit allocates new nodes over copied text.
4. **Piece tree** (VS Code, 2018): the original file stays in read-only
   chunks, typing appends to an "added" buffer, and a balanced tree of pieces
   that know their character and line-break counts describes the document.

## Decision

Option 4: a Kotlin port of VS Code's `pieceTreeBase.ts` and `rbTreeBase.ts`,
with their tests, in `core/text` (pure JVM). The MIT notice is in
`core/text/NOTICE` and ships in the APK on the licences screen.

## Consequences

- Typing appends a few characters and splits one piece, whatever the file
  size; "go to line 80,000" is O(log n).
- Snapshots are cheap and immutable, so saving, diffing, colouring and Preview
  read on other threads without copying the document.
- Line endings are recorded and written back byte for byte; undo records
  "removed this, inserted that", so its memory follows the edit, not the file.
- A large, subtle data structure to own. It is covered by the ported VS Code
  tests plus seeded property tests against a `StringBuilder` reference, and is
  above the 90% coverage gate.
