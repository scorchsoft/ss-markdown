# ADR 0009: Which fonts ship in the APK

- **Status:** Accepted
- **Date:** 2026-11-01
- **Deciders:** Andrew (Scorchsoft)

## Context

Themes need good faces for prose, UI and code, offline from first launch. The
app may download any Google Font the user picks (the only network use,
`product-journey.md`), and the APK size budget is a guideline, not a hard
limit: shrink only where nothing regresses (`quality-bar.md` §4).

## Options

1. **Bundle nothing; download every face on first use.** Smallest APK, but the
   default themes would look wrong offline and on first launch.
2. **Bundle whole families.** Every script and weight; several MB each.
3. **Bundle a curated set, subset to Latin, and download the rest.**

## Decision

Option 3 (`feature/fonts`, `tools/fetch-fonts.sh`, NOTICE):

- **Bundled, subset to Latin, variable weight:** Inter (UI and sans), JetBrains
  Mono and Fira Code (code), Source Serif 4 and Literata (reading), Atkinson
  Hyperlegible Next (legibility). About 1.4 MB compressed. Subsetting makes
  them Modified Versions under the OFL, which is allowed only for families
  without a Reserved Font Name.
- **Reserved Font Names are never modified.** Lora and IBM Plex Sans declare
  RFNs, so they could only ship whole; they are downloaded unmodified from
  Google Fonts when a theme first asks for them, then cached.
- **Any other Google Font** is downloaded on request; the catalogue
  (`assets/fonts/google-fonts.tsv`) ships in the APK so searching is offline
  and private.
- Every family's OFL text ships in the APK, bundled or not, and is shown on the
  licences screen.

## Consequences

- Every built-in theme looks right offline and on first launch.
- Bundled fonts are not traded for size (Andrew, 2026-11-01): dropping a
  reading font would make it need the network the first time, which is a
  regression. The performance review's proposal to download the serif
  families instead (PERF-4) is therefore declined.
- A new bundled family must have no RFN (or ship unmodified), be added through
  `fetch-fonts.sh` with its manifest row and checksum, and get a licences row
  with its OFL text.
