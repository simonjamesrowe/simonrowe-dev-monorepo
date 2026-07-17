import { expect, test, type Page } from '@playwright/test'

/**
 * Primary, deterministic e2e: drives the real chat drawer against a running local full
 * stack (frontend + backend). Bring the stack up first (./scripts/start.sh). Assertions
 * target structure/behaviour, not exact model wording, so a live LLM stays deterministic.
 *
 * Regression coverage for the chat fix-up:
 *  - exactly one assistant bubble per prompt (double-answer bug)
 *  - coherent/ordered answer text (scramble bug)
 *  - contextual tool label, not "Used 1 tool"
 *  - the skills widget renders
 *  - link/image render rules (internal in-site nav, no fabricated/unsafe live links)
 */

async function openChatAndAsk(page: Page, question: string): Promise<void> {
  await page.goto('/')
  await page.getByTestId('open-chat').click()
  await expect(page.getByTestId('chat-panel')).toBeVisible()

  const input = page.getByPlaceholder('Type a message...')
  // Wait until the socket has connected (input is disabled until then).
  await expect(input).toBeEnabled({ timeout: 30_000 })
  await input.fill(question)
  await input.press('Enter')
}

async function waitForAnswerComplete(page: Page): Promise<void> {
  // The input is re-enabled once streaming finalizes.
  const input = page.getByPlaceholder('Type a message...')
  await expect(input).toBeEnabled({ timeout: 80_000 })
}

test('skills question yields one clean answer, a contextual tool label, and the skills widget', async ({ page }) => {
  await openChatAndAsk(
    page,
    'What software development skills does he have on the front end and the back end?',
  )
  await waitForAnswerComplete(page)

  // Exactly one assistant bubble (regression: double-answer).
  const assistantBubbles = page.getByTestId('chat-message-assistant')
  await expect(assistantBubbles).toHaveCount(1)

  // Answer text is coherent and non-trivial (regression: scramble). We assert structure,
  // not wording: the bubble contains a reasonable amount of readable text.
  const answerText = (await assistantBubbles.first().innerText()).trim()
  expect(answerText.length).toBeGreaterThan(20)

  // Contextual tool label present, never the "Used 1 tool" placeholder.
  await expect(page.getByTestId('tool-activity').filter({ hasText: "Looking up Simon's skills" })).toBeVisible()
  await expect(page.getByText('Used 1 tool')).toHaveCount(0)

  // The skills widget rendered.
  await expect(page.locator('.chat-widget--skills')).toBeVisible()
})

test('answers never render a fabricated or unsafe live link', async ({ page }) => {
  await openChatAndAsk(page, 'What have you been blogging about recently? Share links.')
  await waitForAnswerComplete(page)

  const bubble = page.getByTestId('chat-message-assistant').first()

  // No dangerous schemes ever become live anchors.
  await expect(bubble.locator('a[href^="javascript:"]')).toHaveCount(0)
  await expect(bubble.locator('a[href^="data:"]')).toHaveCount(0)

  // Every external anchor that is rendered is a safe new-tab link.
  const externalLinks = bubble.locator('a[target="_blank"]')
  const externalCount = await externalLinks.count()
  for (let i = 0; i < externalCount; i += 1) {
    await expect(externalLinks.nth(i)).toHaveAttribute('rel', /noopener/)
  }

  // If the answer produced an internal link, clicking it navigates in-site (no full reload).
  const internalLinks = bubble.locator('a[href^="/"]')
  if (await internalLinks.count() > 0) {
    const href = await internalLinks.first().getAttribute('href')
    await internalLinks.first().click()
    await expect(page).toHaveURL(new RegExp(`${href}`.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
})
