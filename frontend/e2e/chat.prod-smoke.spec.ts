import { expect, test } from '@playwright/test'

/**
 * Secondary, read-only smoke check against the deployed site. Confirms the chat drawer
 * opens, connects over WebSocket, and returns a non-empty answer. No data mutation.
 *
 * Run with: npm run e2e:prod-smoke  (override target via E2E_PROD_URL).
 */
test('prod chat drawer connects and returns a non-empty answer', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('open-chat').click()
  await expect(page.getByTestId('chat-panel')).toBeVisible()

  const input = page.getByPlaceholder('Type a message...')
  await expect(input).toBeEnabled({ timeout: 30_000 })
  await input.fill('In one sentence, who is Simon?')
  await input.press('Enter')

  // A single assistant bubble with non-empty text confirms the WS round-trip worked.
  const assistantBubble = page.getByTestId('chat-message-assistant').first()
  await expect(assistantBubble).toBeVisible({ timeout: 80_000 })
  await expect(input).toBeEnabled({ timeout: 80_000 })
  const text = (await assistantBubble.innerText()).trim()
  expect(text.length).toBeGreaterThan(0)
})
