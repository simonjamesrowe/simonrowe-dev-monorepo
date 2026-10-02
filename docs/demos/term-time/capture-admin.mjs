// The portfolio page's admin screenshot: Paste a note, filled in but not saved.
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

const OUT = process.argv[2];
const browser = await chromium.launch();
const context = await browser.newContext({
  viewport: { width: 1280, height: 860 }, deviceScaleFactor: 2, colorScheme: 'light',
  locale: 'en-GB', timezoneId: 'Europe/London',
  storageState: new URL('./auth.json', import.meta.url).pathname,
});
const page = await context.newPage();
await page.goto('http://localhost:5173/admin/school/notes');
const box = page.getByLabel('The messages');
await box.waitFor();
await box.fill('Hilltop Academy open evening, Thursday 15 October, 6pm to 8pm.\n'
  + 'Year 6 families welcome, no need to book.\n\n'
  + 'Riverside School tours every Tuesday morning in October, book at the office.');
await page.mouse.move(0, 0);
await page.evaluate(() => { document.querySelector('#note-text').style.height = '150px'; });
const top = await page.locator('.admin-page__title').boundingBox();
const save = await page.getByRole('button', { name: 'Save note' }).boundingBox();
const form = await page.locator('.admin-page').boundingBox();
const pad = 28;
const png = await page.screenshot({ clip: {
  x: form.x - pad, y: top.y - pad, width: form.width + 2 * pad, height: save.y + save.height - top.y + 2 * pad,
} });
const converter = await (await browser.newContext()).newPage();
const webp = await converter.evaluate(async (data) => {
  const img = new Image(); img.src = `data:image/png;base64,${data}`; await img.decode();
  const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
  c.getContext('2d').drawImage(img, 0, 0);
  return c.toDataURL('image/webp', 0.88).split(',')[1];
}, png.toString('base64'));
const file = path.join(OUT, 'highlight-notes.webp');
fs.writeFileSync(file, Buffer.from(webp, 'base64'));
console.log(file, Math.round(fs.statSync(file).size / 1024), 'KB');
await browser.close();
