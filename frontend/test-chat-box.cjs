const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1200, height: 800 });
  await page.goto('http://localhost:5173');
  
  // Wait for the hero chat input to render
  await page.waitForSelector('.hero__chat-input');
  
  // Take a screenshot of the hero chat input
  const element = await page.$('.hero__chat-input');
  if (element) {
    await element.screenshot({ path: 'chat-box-screenshot.png', padding: 20 });
    console.log('Screenshot saved to chat-box-screenshot.png');
  } else {
    console.log('Element not found');
  }
  
  await browser.close();
})();
