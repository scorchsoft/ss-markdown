# Design review 4: final gate check

**What was reviewed.** A fresh reviewer agent checked the design on
2026-11-01 against the design gate in `rubric.md`. It was told not to
open reviews 1–3. It read 32 boards and measured element positions
with Playwright.

**Result:**
- **No Critical findings.** Every path that could lose work keeps a
  copy, confirms first, or skips and lists.
- **Two Major findings.** Both were contradictions between the spec and
  the mockups.
- **J1–J10 all pass on paper.**
- **No criterion below 3.** Weighted average 3.52, the ceiling for a
  design, since #4, #5 and #11 need code.

| # | Finding | Decision |
|---|---|---|
| M1 | Copy jumped about 21 dp when Save changed state | **Fixed.** The Save slot is a fixed 96 dp, sized for "Save as…", with content right-aligned. Measured: Copy's right edge is identical in every state and on every board. |
| M2 | Strip keys gave no peek; in Preview, Undo and Done sat off-screen | **Fixed.** Every strip cuts its last scrolling key at about 50%, with a fade. Code pins Undo and `{ }`; Preview and Table pin Undo and Done. Measured: nothing past x=390. |
| 1–12 | J7/J9 wording, status wording, the symbol list, the Replace toggle, the folder-mode header rule, A4 pinch behaviour, zoom chips on zoomed boards, three sub-token targets, stale "to follow" items, rubric formatting, a wrapping banner | **All fixed.** |

**Gate status: PASSED.**
- No open Critical or Major findings.
- J1–J10 pass.
- No criterion below 3 (excluding the code-only #4, #5 and #11).
- Sign-off by Andrew, delegated to Claude's judgement (2026-11-01).

The build starts at `roadmap.md` iteration 1.
