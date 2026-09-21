import { expect, test } from '@playwright/test'

test('CoParent host boots the React application', async ({ page }) => {
  const pageErrors: Error[] = []
  page.on('pageerror', error => pageErrors.push(error))

  await page.goto('/')

  await expect(page.getByRole('heading', { name: 'CoParent' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sign In' })).toBeVisible()
  expect(pageErrors).toEqual([])
})

test('CoParent host serves its shell for root and deep links', async ({ request }) => {
  for (const path of ['/', '/calendar', '/messages', '/auth/callback', '/invitations/accept']) {
    const response = await request.get(path, { headers: { Accept: 'text/html' } })
    expect(response.status(), path).toBe(200)
    const html = await response.text()
    expect(html, path).toContain('<title>CoParent</title>')
    expect(html, path).toContain('/src/coparent/main.tsx')
  }
})

test('CoParent install metadata is namespaced', async ({ request }) => {
  const response = await request.get('/coparent.webmanifest')
  expect(response.status()).toBe(200)
  const manifest = await response.json()
  expect(manifest.name).toBe('CoParent - Co-Parenting Platform')
  expect(manifest.start_url).toBe('/dashboard')
  expect(manifest.scope).toBe('/')
  expect(manifest.icons).toEqual(expect.arrayContaining([
    expect.objectContaining({
      src: '/coparent-pwa-192x192.png',
      sizes: '192x192',
      type: 'image/png',
    }),
  ]))
})

test('CoParent API requests never fall through to the SPA', async ({ request }) => {
  const response = await request.get('/api/coparent/me')
  // A full local stack returns 401 or the feature-flag 503. A frontend-only routing run
  // returns Vite's upstream 500. All three prove the request was proxied rather than served
  // as the CoParent SPA shell.
  expect([401, 500, 503]).toContain(response.status())
  expect(await response.text()).not.toContain('<title>CoParent</title>')
})
