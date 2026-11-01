## What and why

<!-- What changes, and the reason. Link the issue or review finding (e.g. PERF-1). -->

## How it was tested

<!-- Test names, commands run, screenshots for UI changes (light and dark). -->

## Checklist

- [ ] `gradle -p apps/android check` passes (ktlint, detekt, lint, tests, coverage, module graph)
- [ ] A bug fix starts with a test that failed before it, and adds or updates a row in `docs/edge-cases.md`
- [ ] Public API has KDoc saying why; comments explain decisions, not code
- [ ] Preview page changed? `npm test` passes and the rebuilt `assets/preview` is in this PR
- [ ] Touches files, saving or the editor? `docs/robustness.md` re-read and still honoured
- [ ] New dependency? Pinned, justified in the version catalogue or an ADR, and listed in `licences.txt` with its licence text
- [ ] Significant decision? An ADR in `docs/adr/`
- [ ] User-visible change? A line in `CHANGELOG.md` under Unreleased
