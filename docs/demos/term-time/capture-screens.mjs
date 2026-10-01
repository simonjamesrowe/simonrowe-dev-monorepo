// Captures the portfolio page's Term Time screenshots from production (read-only questions).
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

const OUT = process.argv[2];
const SITE = 'https://term-time.simonrowe.dev/';
const browser = await chromium.launch();
const converter = await (await browser.newContext()).newPage();

async function saveWebp(png, name) {
  const b64 = await converter.evaluate(async (data) => {
    const img = new Image();
    img.src = `data:image/png;base64,${data}`;
    await img.decode();
    const canvas = document.createElement('canvas');
    canvas.width = img.width; canvas.height = img.height;
    canvas.getContext('2d').drawImage(img, 0, 0);
    return canvas.toDataURL('image/webp', 0.88).split(',')[1];
  }, png.toString('base64'));
  const file = path.join(OUT, name);
  fs.writeFileSync(file, Buffer.from(b64, 'base64'));
  console.log(file, Math.round(fs.statSync(file).size / 1024), 'KB');
}

async function ask(page, question) {
  const before = await page.locator('.chat-message').count();
  await page.getByRole('textbox', { name: 'Type a message...' }).fill(question);
  await page.keyboard.press('Enter');
  // The answer is done when the typing indicator has gone and the text has stopped changing.
  await page.locator('.chat-message').nth(before + 1).waitFor();
  await page.waitForTimeout(1500);
  await page.locator('.chat-typing-indicator').waitFor({ state: 'detached', timeout: 90000 });
  let last = '';
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(2000);
    const now = await page.locator('.chat-message').last().innerText();
    if (now === last && page.locator('.chat-message').last().locator('a').count()) break;
    last = now;
  }
  await page.mouse.move(0, 0);
  await page.waitForTimeout(500);
}

async function session() {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2, colorScheme: 'light', locale: 'en-GB', timezoneId: 'Europe/London' });
  const page = await context.newPage();
  await page.goto(SITE);
  await page.getByRole('textbox', { name: 'Type a message...' }).waitFor();
  return { context, page };
}

const column = (y, height) => ({ x: 236, y, width: 808, height });

// Half term, whole school.
{
  const { context, page } = await session();
  await ask(page, 'When is half term?');
  console.log((await page.locator('.chat-message').last().innerText()).slice(0, 300));
  await saveWebp(await page.screenshot({ clip: column(20, 760) }), 'hero.webp');
  const top = (await page.locator('.chat-message').first().boundingBox()).y - 16;
  await saveWebp(await page.screenshot({ clip: column(top, 539) }), 'highlight-term-dates.webp');
  await context.close();
}

// Year 6 selected, next trip.
{
  const { context, page } = await session();
  await page.getByText('Year 6', { exact: true }).click();
  await ask(page, 'When is the next school trip?');
  console.log((await page.locator('.chat-message').last().innerText()).slice(0, 400));
  const top = (await page.getByText('Which year groups?').boundingBox()).y - 12;
  await saveWebp(await page.screenshot({ clip: column(top, 539), fullPage: true }), 'highlight-year-groups.webp');
  await page.screenshot({ path: path.join(path.dirname(new URL(import.meta.url).pathname), '.build', 'year6-full.png'), fullPage: true });
  await context.close();
}

await browser.close();
