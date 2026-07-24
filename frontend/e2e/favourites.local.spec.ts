import { expect, test } from '@playwright/test'

/**
 * End-to-end validation of the favourites feature (spec 029) against a running local
 * stack (frontend 5173 + backend 8080 + MongoDB), driving the REAL Auth0 login popup.
 *
 * Requires owner credentials via env vars so nothing sensitive lives in the repo:
 *   E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD
 * The test is skipped when they are absent.
 *
 * Covers, in one journey (state persists server-side between steps):
 *  - Hearts render empty while logged out (US3/AS5).
 *  - Logged-out heart click opens the Auth0 popup; the page never navigates (US3/AS1).
 *  - Completing login auto-completes the pending save — heart fills, PUT issued (US3/AS2).
 *  - "Show favourites only" lists exactly the saved article (US2/AS1).
 *  - Unsaving inside favourites-only mode removes the card immediately (US1/AS3).
 *  - Empty favourites state renders, not an error (US2/AS5).
 *  - Toggling off restores the full feed (US2/AS2).
 */
const EMAIL = process.env.E2E_ADMIN_EMAIL
const PASSWORD = process.env.E2E_ADMIN_PASSWORD

test.describe('favourites', () => {
  test.skip(!EMAIL || !PASSWORD, 'Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD to run')

  test('logged-out save via login popup, favourites-only filter, unsave', async ({ page }) => {
    await page.goto('/news-events')

    const hearts = page.locator('button.favourite-button')
    await hearts.first().waitFor()
    const totalHearts = await hearts.count()
    expect(totalHearts).toBeGreaterThan(1)

    // Logged out: hearts render empty.
    const firstHeart = hearts.first()
    await expect(firstHeart).toHaveAttribute('aria-pressed', 'false')

    // Clicking a heart opens the Auth0 login popup; the page itself must not navigate.
    const popupPromise = page.waitForEvent('popup')
    await firstHeart.click()
    const popup = await popupPromise
    await popup.waitForLoadState('domcontentloaded')
    await expect(page).toHaveURL(/\/news-events$/)

    // Complete the login inside the popup.
    await popup.getByRole('textbox', { name: 'Email address' }).fill(EMAIL!)
    await popup.getByRole('textbox', { name: 'Password' }).fill(PASSWORD!)
    await popup.getByRole('button', { name: 'Continue', exact: true }).click()

    // The pending save completes automatically — no further clicks.
    await expect(firstHeart).toHaveAttribute('aria-pressed', 'true')
    await expect(page).toHaveURL(/\/news-events$/)

    // Favourites-only mode shows exactly the saved article.
    const toggle = page.getByRole('button', { name: 'Show favourites only' })
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-pressed', 'true')
    const favouritesOnlyHearts = page.locator('button.favourite-button[aria-pressed="true"]')
    await expect(favouritesOnlyHearts).toHaveCount(1)
    await expect(page.locator('button.favourite-button')).toHaveCount(1)

    // Unsaving inside favourites-only mode removes the card immediately and shows the
    // empty state (not an error).
    await favouritesOnlyHearts.first().click()
    await expect(page.locator('button.favourite-button')).toHaveCount(0)
    await expect(page.getByText('No favourites yet', { exact: false })).toBeVisible()

    // Toggling off restores the full feed.
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-pressed', 'false')
    await expect(page.locator('button.favourite-button')).toHaveCount(totalHearts)
  })
})
