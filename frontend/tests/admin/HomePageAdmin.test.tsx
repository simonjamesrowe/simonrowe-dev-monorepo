import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { HomePageAdmin } from '../../src/pages/admin/HomePageAdmin'
import { DEFAULT_HOME_PAGE } from '../../src/types/homePage'

vi.mock('../../src/services/adminApi', async () => {
  const actual = await vi.importActual<typeof import('../../src/services/adminApi')>(
    '../../src/services/adminApi',
  )
  return {
    AdminValidationError: actual.AdminValidationError,
    fetchAdminHomePage: vi.fn(),
    updateAdminHomePage: vi.fn(),
    fetchAdminProfile: vi.fn(),
  }
})
// One stable function, as the real hook provides: a fresh one per render would re-run the
// editor's load effect on every keystroke and reset the form.
const getAccessToken = vi.fn()
vi.mock('../../src/auth/useAuth', () => ({ useAuth: () => ({ getAccessToken }) }))
vi.mock('../../src/hooks/useUnsavedChanges', () => ({ useUnsavedChanges: vi.fn() }))

import {
  AdminValidationError,
  fetchAdminHomePage,
  fetchAdminProfile,
  updateAdminHomePage,
} from '../../src/services/adminApi'

function renderEditor() {
  return render(
    <MemoryRouter>
      <HomePageAdmin />
    </MemoryRouter>,
  )
}

describe('HomePageAdmin', () => {
  beforeEach(() => {
    vi.mocked(fetchAdminHomePage).mockReset().mockResolvedValue(DEFAULT_HOME_PAGE)
    vi.mocked(updateAdminHomePage).mockReset()
    vi.mocked(fetchAdminProfile).mockReset().mockResolvedValue({
      backgroundImage: { url: '/uploads/desktop.jpg' },
      mobileBackgroundImage: null,
    } as never)
  })

  it('says when it is showing the defaults, and shows the profile images read-only', async () => {
    renderEditor()
    expect(await screen.findByText(/built-in default copy/i)).toBeInTheDocument()
    expect(screen.getByText('None set')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Profile' })).toHaveAttribute('href', '/admin/profile')
  })

  it('sends every field back on save, edited or not', async () => {
    const user = userEvent.setup()
    vi.mocked(updateAdminHomePage).mockImplementation(async (_token, data) => ({
      ...data,
      updatedAt: '2026-09-29T10:00:00Z',
    }))
    renderEditor()

    const line1 = await screen.findByLabelText(/Headline, first line/)
    await user.clear(line1)
    await user.type(line1, 'New line.')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(updateAdminHomePage).toHaveBeenCalledTimes(1))
    expect(vi.mocked(updateAdminHomePage).mock.calls[0][1]).toEqual({
      ...DEFAULT_HOME_PAGE,
      headlineLine1: 'New line.',
    })
    expect(await screen.findByText('Home page saved.')).toBeInTheDocument()
  })

  it('shows each server field error beside its input', async () => {
    const user = userEvent.setup()
    vi.mocked(updateAdminHomePage).mockRejectedValue(new AdminValidationError('bad', [
      { field: 'primaryCta.href', message: 'primaryCta.href must be a site path starting with / or an https:// address' },
    ]))
    renderEditor()

    const href = await screen.findByLabelText(/Primary button link/)
    await user.clear(href)
    await user.type(href, '//evil.example')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('must be a site path')
    expect(href).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('Some fields need attention.')).toBeInTheDocument()
  })

  it('hides the tour label when the tour link is switched off, and keeps Save disabled until an edit', async () => {
    const user = userEvent.setup()
    renderEditor()
    expect(await screen.findByLabelText(/Tour link/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: /Show the tour link/ }))
    expect(screen.queryByLabelText(/^Tour link/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })
})
