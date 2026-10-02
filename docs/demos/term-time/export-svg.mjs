// Renders diagrams/<name>.json with the demo renderer and writes the SVG Excalidraw exports.
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';
import { startDiagramServer } from '/Users/simonrowe/.claude/skills/demo-record/scripts/diagram.mjs';

const [outDir, ...names] = process.argv.slice(2);
const demoDir = path.dirname(new URL(import.meta.url).pathname);
const server = await startDiagramServer(demoDir, path.join(demoDir, '.build'));
const browser = await chromium.launch();
const page = await browser.newPage();
try {
  for (const name of names) {
    await page.goto(`${server.base}/diagram/${name}`);
    await page.waitForFunction(() => window.__diagram && (window.__diagram.ready || window.__diagram.error));
    const error = await page.evaluate(() => window.__diagram.error);
    if (error) throw new Error(`${name}: ${error}`);
    const svg = await page.evaluate(() => document.querySelector('#cam > svg').outerHTML);
    const file = path.join(outDir, `${name}.svg`);
    fs.writeFileSync(file, `<?xml version="1.0" encoding="UTF-8"?>\n${svg}\n`);
    console.log(file, Math.round(svg.length / 1024), 'KB');
  }
} finally {
  await browser.close();
  await server.close();
}
