import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { AdminMenu, type AdminMenuItem } from '../../src/components/admin/AdminMenu'

describe('AdminMenu', () => {
  let onExport: ReturnType<typeof vi.fn>
  let onArchive: ReturnType<typeof vi.fn>
  let items: AdminMenuItem[]

  beforeEach(() => {
    onExport = vi.fn()
    onArchive = vi.fn()
    items = [
      { label: 'Export', title: 'Export everything', onSelect: onExport },
      { label: 'Archive', onSelect: onArchive, disabled: true },
    ]
  })

  const openMenu = () => {
    fireEvent.click(screen.getByRole('button', { name: /Actions/ }))
  }

  it('renders the trigger with the popover closed initially', () => {
    render(<AdminMenu label="Actions" items={items} />)

    const trigger = screen.getByRole('button', { name: /Actions/ })
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu')
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(screen.queryByText('Export')).not.toBeInTheDocument()
  })

  it('opens the popover and shows item labels when the trigger is clicked', () => {
    render(<AdminMenu label="Actions" items={items} />)

    openMenu()

    expect(screen.getByRole('menu')).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Export' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Archive' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Export' })).toHaveAttribute(
      'title',
      'Export everything',
    )
    expect(screen.getByRole('button', { name: /Actions/ })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
  })

  it('calls onSelect and closes the popover when an item is clicked', () => {
    render(<AdminMenu label="Actions" items={items} />)

    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Export' }))

    expect(onExport).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('closes the popover on Escape', () => {
    render(<AdminMenu label="Actions" items={items} />)

    openMenu()
    expect(screen.getByRole('menu')).toBeInTheDocument()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(onExport).not.toHaveBeenCalled()
  })

  it('closes the popover on a click outside the menu', () => {
    render(
      <div>
        <AdminMenu label="Actions" items={items} />
        <button type="button">Outside</button>
      </div>,
    )

    openMenu()
    expect(screen.getByRole('menu')).toBeInTheDocument()

    fireEvent.mouseDown(screen.getByRole('button', { name: 'Outside' }))

    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('does not fire onSelect for a disabled item', () => {
    render(<AdminMenu label="Actions" items={items} />)

    openMenu()
    const archive = screen.getByRole('menuitem', { name: 'Archive' })
    expect(archive).toBeDisabled()

    fireEvent.click(archive)

    expect(onArchive).not.toHaveBeenCalled()
    expect(screen.getByRole('menu')).toBeInTheDocument()
  })
})
