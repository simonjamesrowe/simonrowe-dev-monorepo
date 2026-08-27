import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

describe('VersionBadge', () => {
  async function renderBadge(sha?: string) {
    vi.resetModules()
    vi.doMock('../src/config/version', () => ({
      FRONTEND_SHORT_COMMIT: sha ? sha.slice(0, 7) : 'dev',
    }))
    const { VersionBadge } = await import('../src/components/layout/VersionBadge')
    render(
      <MemoryRouter>
        <VersionBadge />
      </MemoryRouter>,
    )
  }

  it('renders an icon-only link, not the SHA as visible text', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByRole('link').textContent).toBe('')
    expect(screen.queryByText('840c311')).not.toBeInTheDocument()
  })

  it('links to the status page', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByRole('link')).toHaveAttribute('href', '/status')
  })

  it('carries an accessible name containing the version', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByRole('link')).toHaveAccessibleName(/840c311/)
  })

  it('keeps the aria-label and title identical, like its neighbouring footer icons', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    const link = screen.getByRole('link')
    expect(link.getAttribute('title')).toBe(link.getAttribute('aria-label'))
  })

  it('still renders when no SHA is baked in, labelled as a dev build', async () => {
    await renderBadge(undefined)

    const link = screen.getByRole('link')
    expect(link).toHaveAttribute('href', '/status')
    expect(link).toHaveAccessibleName(/dev build/i)
  })
})
