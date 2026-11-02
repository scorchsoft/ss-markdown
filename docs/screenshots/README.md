# Screenshots

Generated, never hand-made. The Robolectric screenshot harness renders
each key screen in **light and dark** at 412 × 915:

```sh
gradle -p apps/android -Pssmarkdown.screenshots=true :app:testDebugUnitTest
```

The PNGs land in `apps/android/app/build/screenshots/`. For each
release, copy the curated set here as `<screen>-light.png` and
`<screen>-dark.png`, downscaled to about 600 px wide to keep the repo
small. The README links to them.

The Robolectric harness cannot run a WebView, so its Preview pane is a
stand-in. The Preview images here come from the page's own Playwright
theme tests instead (CI artifact `preview-theme-screenshots`, or
`npm test` in `apps/android/feature/preview/web`), cropped to one phone
screen. Render the app set with `--rerun --no-build-cache`: a test
result taken from the build cache writes no PNGs.
