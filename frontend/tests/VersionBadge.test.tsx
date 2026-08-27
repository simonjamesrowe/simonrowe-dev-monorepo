import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

describe('VersionBadge', () => {
  async function renderBadge(sha?: string, buildTime?: string) {
    vi.resetModules()
    vi.doMock('../src/config/version', () => ({
      FRONTEND_COMMIT: sha ?? 'unknown',
      FRONTEND_SHORT_COMMIT: sha ? sha.slice(0, 7) : 'dev',
      FRONTEND_BUILD_TIME: buildTime ?? null,
    }))
    const { VersionBadge } = await import('../src/components/layout/VersionBadge')
    render(
      <MemoryRouter>
        <VersionBadge />
      </MemoryRouter>,
    )
  }

  it('renders the bundle short SHA', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByText('840c311')).toBeInTheDocument()
  })

  it('links to the status page', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByRole('link')).toHaveAttribute('href', '/status')
  })

  it('renders a dev build when no SHA was baked in', async () => {
    await renderBadge(undefined)

    expect(screen.getByText('dev')).toBeInTheDocument()
  })

  it('carries an accessible name explaining what the SHA is', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByRole('link')).toHaveAccessibleName(/version/i)
  })

  it('puts the build time in the title when it is known', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a', '2026-08-26T14:02:11Z')

    expect(screen.getByRole('link').getAttribute('title')).toMatch(/2026/)
  })

  it('omits the build time from the title when it is unknown', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByRole('link').getAttribute('title')).not.toMatch(/\d{4}/)
  })
})
