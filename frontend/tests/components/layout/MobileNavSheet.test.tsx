import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { MobileNavSheet } from '../../../src/components/layout/MobileNavSheet'
import { visibleGroups } from '../../../src/components/layout/navModel'

const openChat = vi.fn()

vi.mock('../../../src/contexts/ChatContext', () => ({ useChat: () => ({ openChat }) }))
vi.mock('../../../src/contexts/ThemeContext', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: vi.fn() }),
}))
vi.mock('../../../src/components/search/SiteSearch', () => ({
  SiteSearch: () => <input aria-label="Search or ask a question" type="search" />,
}))

function renderSheet(props: Partial<Parameters<typeof MobileNavSheet>[0]> = {}, path = '/') {
  const onClose = vi.fn()
  const result = render(
    <MemoryRouter initialEntries={[path]}>
      <MobileNavSheet
        groups={visibleGroups()}
        isAdmin={false}
        onClose={onClose}
        open
        {...props}
      />
    </MemoryRouter>,
  )
  return { ...result, onClose }
}

describe('MobileNavSheet', () => {
  beforeEach(() => {
    openChat.mockReset()
    document.body.style.overflow = ''
  })

  it('renders nothing while closed, so there is no second search field in the document', () => {
    renderSheet({ open: false })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  })

  it('puts search first, then the groups', () => {
    renderSheet()
    const dialog = screen.getByRole('dialog', { name: 'Menu' })
    const search = screen.getByRole('searchbox')
    const firstGroup = screen.getByRole('button', { name: 'About' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(search.compareDocumentPosition(firstGroup) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('expands one group at a time and pre-expands the one holding the current page', async () => {
    const user = userEvent.setup()
    renderSheet({}, '/blogs')
    expect(screen.getByRole('button', { name: 'Insights' })).toHaveAttribute('aria-expanded', 'true')

    await user.click(screen.getByRole('button', { name: 'About' }))
    expect(screen.getByRole('button', { name: 'About' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: 'Insights' })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('link', { name: 'Experience' })).toHaveAttribute('href', '/about#roles')
  })

  it('locks page scroll while open and restores it on close', () => {
    const { unmount } = renderSheet()
    expect(document.body.style.overflow).toBe('hidden')
    unmount()
    expect(document.body.style.overflow).toBe('')
  })

  it('closes on Escape and when a destination is chosen', async () => {
    const user = userEvent.setup()
    const { onClose } = renderSheet()
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: 'Under the hood' }))
    await user.click(screen.getByRole('link', { name: 'MCP server' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('focuses the close button by default and the search field for the shortcut', () => {
    const { unmount } = renderSheet()
    expect(screen.getByRole('button', { name: 'Close menu' })).toHaveFocus()
    unmount()

    renderSheet({ focusSearch: true })
    expect(screen.getByRole('searchbox')).toHaveFocus()
  })

  it('keeps Tab inside the sheet', async () => {
    const user = userEvent.setup()
    renderSheet()
    const close = screen.getByRole('button', { name: 'Close menu' })
    const ask = screen.getByRole('button', { name: 'Ask Simon anything' })
    ask.focus()
    await user.tab()
    // The brand link comes first in the sheet, then the close button.
    expect(screen.getByRole('link', { name: 'Simon Rowe homepage' })).toHaveFocus()
    await user.tab({ shift: true })
    expect(ask).toHaveFocus()
    expect(close).toBeInTheDocument()
  })

  it('opens the chat from the pinned button, and offers no tour (the tour is desktop-only)', async () => {
    const user = userEvent.setup()
    const { onClose } = renderSheet()
    expect(screen.queryByRole('button', { name: 'Take a tour' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Ask Simon anything' }))
    expect(onClose).toHaveBeenCalled()
    expect(openChat).toHaveBeenCalledWith()
  })
})
