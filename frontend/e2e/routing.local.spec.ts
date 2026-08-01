import { expect, test } from '@playwright/test'

/**
 * Routing and blog-tab smoke tests against a running local stack.
 *
 * Requires the frontend (5173) and backend (8080) to be up — there is no `webServer`
 * block in playwright.config.ts, so bring the stack up with `./scripts/start.sh` first.
 *
 * Assertions target structure and URLs, never content wording, so the suite stays
 * deterministic against real data.
 */

test.describe('routing', () => {
  test('the legacy /blog path redirects to the canonical listing', async ({ page }) => {
    await page.goto('/blog')

    await expect(page).toHaveURL(/\/blogs$/)
    await expect(page.locator('.blog-listing-page')).toBeVisible()
  })

  test('a legacy /blog/:id path redirects to the canonical post address', async ({ page }) => {
    // Take a real id from the API so the test does not depend on fixture data.
    const response = await page.request.get('/api/blogs')
    expect(response.ok()).toBeTruthy()
    const blogs = (await response.json()) as Array<{ id: string }>
    expect(blogs.length).toBeGreaterThan(0)
    const { id } = blogs[0]

    await page.goto(`/blog/${id}`)

    await expect(page).toHaveURL(new RegExp(`/blogs/${id}$`))
  })

  test('an unknown URL renders the not-found page inside the site chrome', async ({ page }) => {
    await page.goto('/this-page-does-not-exist')

    await expect(page.getByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible()
    // Inside the normal layout, not a bare page.
    await expect(page.locator('nav.top-nav')).toBeVisible()
    await expect(page.locator('footer.footer')).toBeVisible()
    await expect(page.getByRole('link', { name: /back to home/i })).toBeVisible()
    await expect(page.getByRole('link', { name: /read the blog/i })).toBeVisible()
    await expect(page).toHaveTitle(/Page not found/)
  })

  test('every public page ends in the site footer', async ({ page }) => {
    for (const path of ['/', '/profile', '/experience', '/blogs', '/news-events', '/mcp']) {
      await page.goto(path)
      await expect(page.locator('footer.footer'), `footer missing on ${path}`).toBeVisible()
    }
  })
})

test.describe('blog content tabs', () => {
  test('the listing opens on Engineering and can switch to Weekly Digest', async ({ page }) => {
    await page.goto('/blogs')

    const engineering = page.getByRole('tab', { name: 'Engineering' })
    await expect(engineering).toHaveAttribute('aria-selected', 'true')

    const digest = page.getByRole('tab', { name: 'Weekly Digest' })
    await digest.click()
    await expect(digest).toHaveAttribute('aria-selected', 'true')
    await expect(engineering).toHaveAttribute('aria-selected', 'false')
  })
})

test.describe('home page', () => {
  test('scrolls past the hero into real content', async ({ page }) => {
    await page.goto('/')

    // The page must be taller than one viewport — it used to be exactly the hero.
    const scrollHeight = await page.evaluate(() => document.body.scrollHeight)
    const viewportHeight = page.viewportSize()?.height ?? 0
    expect(scrollHeight).toBeGreaterThan(viewportHeight * 1.5)

    await expect(page.locator('.employer-logo-strip')).toBeVisible()
    await expect(page.locator('.featured-writing')).toBeVisible()
    await expect(page.locator('.cta-section')).toBeVisible()
  })
})
