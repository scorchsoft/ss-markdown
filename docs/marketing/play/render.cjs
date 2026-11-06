// Renders the Play graphics from the HTML pages in this folder.
//   node render.cjs feature       -> feature-graphic.png (1024 x 500)
//   node render.cjs shots         -> screenshots/*.png (1080 x 1920), from shots.json
// Needs Playwright: pass its path in PLAYWRIGHT (a folder with node_modules/playwright).
const path = require('path')
const { chromium } = require(path.join(process.env.PLAYWRIGHT || '.', 'node_modules/playwright'))
const fs = require('fs')
const here = __dirname
;(async () => {
  const b = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {})
  const what = process.argv[2]
  if (what === 'feature') {
    const p = await b.newPage({ viewport: { width: 1024, height: 500 } })
    await p.goto('file://' + path.join(here, 'feature-graphic.html'))
    await p.evaluate(() => document.fonts.ready); await p.waitForTimeout(300)
    await p.screenshot({ path: path.join(here, 'feature-graphic.png') })
  } else if (what === 'shots') {
    fs.mkdirSync(path.join(here, 'screenshots'), { recursive: true })
    const shots = JSON.parse(fs.readFileSync(path.join(here, 'shots.json'), 'utf8'))
    for (const s of shots) {
      const p = await b.newPage({ viewport: { width: 1080, height: 1920 } })
      const q = new URLSearchParams({ img: 'src/' + s.img, cap: s.cap, y: String(s.y || 0), ...(s.dark ? { dark: '1' } : {}) })
      await p.goto('file://' + path.join(here, 'screenshot.html') + '?' + q)
      await p.waitForFunction(() => window.shotReady); await p.waitForTimeout(200)
      await p.screenshot({ path: path.join(here, 'screenshots', s.out) })
      await p.close()
    }
  }
  await b.close()
})()
