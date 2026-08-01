import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { MobileMenu } from '../../../src/components/layout/MobileMenu'
import { TopNav } from '../../../src/components/layout/TopNav'

const isAdmin = vi.fn<() => boolean>()

vi.mock('../../../src/auth/useAdminRole', () => ({
  useAdminRole: () => isAdmin(),
}))

vi.mock('../../../src/contexts/ChatContext', () => ({
  useChat: () => ({ openChat: vi.fn() }),
}))

vi.mock('../../../src/contexts/ThemeContext', () => ({
  useTheme: () => ({ theme: 'dark', toggleTheme: vi.fn() }),
}))

vi.mock('../../../src/components/search/SiteSearch', () => ({
  SiteSearch: () => null,
}))

const PUBLIC_LINKS = ['Home', 'Profile', 'Experience', 'Blog', 'News & Events', 'MCP']

describe('admin navigation gating', () => {
  beforeEach(() => {
    isAdmin.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('TopNav', () => {
    it('hides the admin link from a visitor who is not an administrator', () => {
      isAdmin.mockReturnValue(false)
      render(
        <MemoryRouter>
          <TopNav />
        </MemoryRouter>,
      )

      expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument()
      // The public links must be unaffected either way.
      PUBLIC_LINKS.forEach((label) => {
        expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
      })
    })

    it('shows the admin link to an administrator', () => {
      isAdmin.mockReturnValue(true)
      render(
        <MemoryRouter>
          <TopNav />
        </MemoryRouter>,
      )

      expect(screen.getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin')
    })
  })

  describe('MobileMenu', () => {
    async function openMenu() {
      await userEvent.click(screen.getByRole('button', { name: 'Open menu' }))
    }

    it('hides the admin item from a visitor who is not an administrator', async () => {
      isAdmin.mockReturnValue(false)
      render(
        <MemoryRouter>
          <MobileMenu />
        </MemoryRouter>,
      )
      await openMenu()

      expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument()
      PUBLIC_LINKS.forEach((label) => {
        expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
      })
    })

    it('shows the admin item to an administrator', async () => {
      isAdmin.mockReturnValue(true)
      render(
        <MemoryRouter>
          <MobileMenu />
        </MemoryRouter>,
      )
      await openMenu()

      expect(screen.getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin')
    })
  })
})
