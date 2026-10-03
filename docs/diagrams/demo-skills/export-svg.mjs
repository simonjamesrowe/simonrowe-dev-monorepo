// Renders docs/diagrams/demo-skills/<name>.json with the demo-record renderer
// and writes <name>.svg beside it (self-contained fonts, white background).
//   node export-svg.mjs pipeline [--png]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const SKILL = path.join(os.homedir(), '.claude/skills/demo-record/scripts/diagram.mjs');
const DEMOS = path.join(os.homedir(), 'workspace/simonjamesrowe/demos');
const { startDiagramServer } = await import(SKILL);
const { chromium } = createRequire(path.join(DEMOS, 'package.json'))('playwright');

const here = path.dirname(new URL(import.meta.url).pathname);
const names = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const png = process.argv.includes('--png');
// Inside the demos workspace, because the renderer resolves Excalidraw from there.
const build = fs.mkdtempSync(path.join(DEMOS, '.diagram-'));
// The renderer reads diagrams/<name>.json from the directory it is given.
fs.mkdirSync(path.join(build, 'diagrams'));
for (const n of names) fs.copyFileSync(path.join(here, `${n}.json`), path.join(build, 'diagrams', `${n}.json`));
const server = await startDiagramServer(build, path.join(build, '.build'));
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
try {
  for (const name of names) {
    await page.goto(`${server.base}/diagram/${name}`);
    await page.waitForFunction(() => window.__diagram && (window.__diagram.ready || window.__diagram.error));
    const error = await page.evaluate(() => window.__diagram.error);
    if (error) throw new Error(`${name}: ${error}`);
    const svg = await page.evaluate(() => document.querySelector('#cam > svg').outerHTML);
    fs.writeFileSync(path.join(here, `${name}.svg`), `<?xml version="1.0" encoding="UTF-8"?>\n${svg}\n`);
    if (png) await page.screenshot({ path: path.join(here, `${name}.png`) });
    console.log(name, Math.round(svg.length / 1024), 'KB');
  }
} finally {
  await browser.close();
  await server.close();
  fs.rmSync(build, { recursive: true, force: true });
}
