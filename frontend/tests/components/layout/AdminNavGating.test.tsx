import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SiteHeader } from '../../../src/components/layout/SiteHeader'

const isAdmin = vi.fn<() => boolean>()

// No network: the header's Portfolio menu and the home carousel both read this.
vi.mock('../../../src/services/portfolioApi', () => ({
  fetchPortfolio: vi.fn().mockResolvedValue([]),
  fetchPortfolioProject: vi.fn().mockResolvedValue(null),
}))

vi.mock('../../../src/auth/useAdminRole', () => ({
  useAdminRole: () => isAdmin(),
}))

vi.mock('../../../src/contexts/ChatContext', () => ({
  useChat: () => ({ openChat: vi.fn() }),
}))

vi.mock('../../../src/contexts/ThemeContext', () => ({
  useTheme: () => ({ theme: 'dark', toggleTheme: vi.fn() }),
}))

vi.mock('../../../src/hooks/useTour', () => ({
  useTour: () => ({ start: vi.fn() }),
}))

vi.mock('../../../src/components/search/SiteSearch', () => ({
  SiteSearch: () => null,
}))

const PUBLIC_MENUS = ['About', 'Insights', 'Under the hood']

function renderHeader() {
  render(
    <MemoryRouter>
      <SiteHeader />
    </MemoryRouter>,
  )
}

describe('admin navigation gating', () => {
  beforeEach(() => {
    isAdmin.mockReset()
  })

  describe('desktop header', () => {
    it('hides the admin link from a visitor who is not an administrator', () => {
      isAdmin.mockReturnValue(false)
      renderHeader()

      expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument()
      // The public menus must be unaffected either way.
      PUBLIC_MENUS.forEach((label) => {
        expect(screen.getByRole('button', { name: label })).toBeInTheDocument()
      })
    })

    it('shows the admin link to an administrator', () => {
      isAdmin.mockReturnValue(true)
      renderHeader()

      expect(screen.getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin')
    })
  })

  describe('phone menu sheet', () => {
    async function openSheet() {
      await userEvent.click(screen.getByRole('button', { name: 'Open menu' }))
      return screen.getByRole('dialog', { name: 'Menu' })
    }

    it('hides the admin item from a visitor who is not an administrator', async () => {
      isAdmin.mockReturnValue(false)
      renderHeader()
      const sheet = await openSheet()

      expect(sheet.querySelector('a[href="/admin"]')).toBeNull()
      PUBLIC_MENUS.forEach((label) => {
        expect(screen.getAllByRole('button', { name: label }).length).toBeGreaterThan(0)
      })
    })

    it('shows the admin item to an administrator', async () => {
      isAdmin.mockReturnValue(true)
      renderHeader()
      const sheet = await openSheet()

      expect(sheet.querySelector('a[href="/admin"]')).toHaveTextContent('Admin')
    })
  })
})
