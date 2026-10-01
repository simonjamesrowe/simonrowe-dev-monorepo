import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../src/services/adminApi', async () => {
  const actual = await vi.importActual<typeof import('../../src/services/adminApi')>(
    '../../src/services/adminApi',
  )
  return {
    AdminValidationError: actual.AdminValidationError,
    fetchAdminPortfolio: vi.fn(),
    fetchAdminPortfolioProject: vi.fn(),
    createAdminPortfolioProject: vi.fn(),
    updateAdminPortfolioProject: vi.fn(),
    deleteAdminPortfolioProject: vi.fn(),
    reorderAdminPortfolio: vi.fn(),
    uploadAdminMedia: vi.fn(),
    fetchAdminMedia: vi.fn().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 }),
  }
})
// One stable function, as the real hook provides.
const getAccessToken = vi.fn()
vi.mock('../../src/auth/useAuth', () => ({ useAuth: () => ({ getAccessToken }) }))
vi.mock('../../src/hooks/useUnsavedChanges', () => ({ useUnsavedChanges: vi.fn() }))

import {
  AdminValidationError,
  createAdminPortfolioProject,
  deleteAdminPortfolioProject,
  fetchAdminPortfolio,
  fetchAdminPortfolioProject,
  reorderAdminPortfolio,
  updateAdminPortfolioProject,
  type AdminPortfolioProject,
} from '../../src/services/adminApi'
import { PortfolioAdmin } from '../../src/pages/admin/PortfolioAdmin'
import { PortfolioProjectEditor } from '../../src/pages/admin/PortfolioProjectEditor'

const row = (id: string, name: string, published = true): AdminPortfolioProject => ({
  id, slug: id, name, tagline: `${name} tagline`, description: null, status: 'COMING_SOON',
  displayOrder: 0, published, image: null, liveUrl: null, accentHue: 200,
  headline: null, summary: null, statement: null, exampleQuestions: [], highlights: [], demo: null, pages: [],
  createdAt: '2026-09-29T00:00:00Z', updatedAt: '2026-09-29T00:00:00Z',
})

/** A project with every page field set, as the API returns it. */
const full = (): AdminPortfolioProject => ({
  ...row('term-time', 'Term Time'),
  status: 'BETA',
  description: 'Body',
  image: { url: '/media/hero.webp' },
  liveUrl: 'https://term-time.simonrowe.dev',
  headline: 'School life,\none question away.',
  summary: 'A summary.',
  statement: { label: 'Why', text: 'Because.', points: [{ title: 'Inbox', text: 'Mail.', imageUrl: null, imageAlt: null }] },
  exampleQuestions: ['When is half term?', 'When do we go back?'],
  highlights: [{ title: 'Dates', text: 'From the feed.', imageUrl: '/media/a.webp', imageAlt: 'A' }],
  demo: {
    title: 'Demo', summary: 'Walkthrough.', videoUrl: '/media/demo.mp4', captionsUrl: '/media/demo.vtt',
    posterUrl: '/media/poster.webp', chapters: [{ startSeconds: 0, label: 'Start' }, { startSeconds: 65, label: 'Admin' }],
  },
  pages: [{ slug: 'how', title: 'How', navHint: 'hint', summary: 'Teaser', body: '| a |\n| - |\n| 1 |\n' }],
})

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<PortfolioAdmin />} path="/admin/portfolio" />
        <Route element={<PortfolioProjectEditor />} path="/admin/portfolio/:id" />
      </Routes>
    </MemoryRouter>,
  )
}

describe('PortfolioAdmin', () => {
  beforeEach(() => {
    vi.mocked(fetchAdminPortfolio).mockReset().mockResolvedValue([row('a', 'Alpha'), row('b', 'Beta', false)])
    vi.mocked(reorderAdminPortfolio).mockReset().mockResolvedValue()
    vi.mocked(deleteAdminPortfolioProject).mockReset().mockResolvedValue()
    vi.mocked(createAdminPortfolioProject).mockReset()
  })

  it('lists projects with published state icons and edit/delete actions', async () => {
    renderAt('/admin/portfolio')
    expect(await screen.findByText('Alpha')).toBeInTheDocument()
    expect(screen.getByLabelText('Published')).toBeInTheDocument()
    expect(screen.getByLabelText('Not published')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit Beta' })).toBeInTheDocument()
  })

  it('sends the new order after a drag and drop', async () => {
    renderAt('/admin/portfolio')
    const rows = (await screen.findAllByRole('row')).slice(1)
    fireEvent.dragStart(rows[1])
    fireEvent.dragOver(rows[0])
    fireEvent.drop(rows[0])
    await waitFor(() => expect(reorderAdminPortfolio).toHaveBeenCalledWith(getAccessToken, ['b', 'a']))
  })

  it('deletes after confirmation', async () => {
    const user = userEvent.setup()
    renderAt('/admin/portfolio')
    await user.click(await screen.findByRole('button', { name: 'Delete Alpha' }))
    // `hidden: true` because the shared ConfirmDialog marks its own backdrop aria-hidden,
    // which hides the dialog from the accessibility tree (a pre-existing fault, not this page's).
    await user.click(screen.getByRole('button', { hidden: true, name: 'Delete' }))
    await waitFor(() => expect(deleteAdminPortfolioProject).toHaveBeenCalledWith(getAccessToken, 'a'))
  })

  it('suggests the address from the name and sends every field on create', async () => {
    const user = userEvent.setup()
    vi.mocked(createAdminPortfolioProject).mockResolvedValue(row('new', 'New'))
    renderAt('/admin/portfolio/new')

    await user.type(await screen.findByLabelText('Name'), "Clinician's Veil")
    expect(screen.getByLabelText('Address')).toHaveValue('clinician-s-veil')
    await user.type(screen.getByLabelText('Tagline'), 'Details soon.')
    await user.click(screen.getByRole('checkbox', { name: 'Published' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(createAdminPortfolioProject).toHaveBeenCalledTimes(1))
    expect(vi.mocked(createAdminPortfolioProject).mock.calls[0][1]).toEqual({
      slug: 'clinician-s-veil',
      name: "Clinician's Veil",
      tagline: 'Details soon.',
      description: '',
      status: 'COMING_SOON',
      displayOrder: null,
      published: true,
      image: null,
      liveUrl: '',
      accentHue: 212,
      headline: '',
      summary: '',
      statement: null,
      exampleQuestions: [],
      highlights: [],
      demo: null,
      pages: [],
    })
  })

  it('saves every page field back unchanged when nothing was edited', async () => {
    const user = userEvent.setup()
    const project = full()
    vi.mocked(fetchAdminPortfolioProject).mockReset().mockResolvedValue(project)
    vi.mocked(updateAdminPortfolioProject).mockReset().mockResolvedValue(project)
    renderAt('/admin/portfolio/term-time')

    expect(await screen.findByLabelText('Questions')).toHaveValue('When is half term?\nWhen do we go back?')
    expect(screen.getByLabelText('Chapters')).toHaveValue('0:00 Start\n1:05 Admin')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(updateAdminPortfolioProject).toHaveBeenCalledTimes(1))
    const editable: Partial<AdminPortfolioProject> = { ...project }
    delete editable.id
    delete editable.createdAt
    delete editable.updatedAt
    expect(vi.mocked(updateAdminPortfolioProject).mock.calls[0][2]).toEqual(editable)
  })

  it('turns the text fields into lists and leaves out an empty demo and statement', async () => {
    const user = userEvent.setup()
    vi.mocked(createAdminPortfolioProject).mockResolvedValue(row('new', 'New'))
    renderAt('/admin/portfolio/new')

    await user.type(await screen.findByLabelText('Name'), 'New')
    await user.type(screen.getByLabelText('Questions'), '  First?  {enter}{enter}Second?')
    await user.click(screen.getByRole('button', { name: 'Add page' }))
    const page = within(screen.getByRole('group', { name: 'Page 1' }))
    await user.type(page.getByLabelText('Tab title'), 'How it works')
    await user.type(page.getByLabelText('Page address'), 'how-it-works')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(createAdminPortfolioProject).toHaveBeenCalledTimes(1))
    const sent = vi.mocked(createAdminPortfolioProject).mock.calls[0][1]
    expect(sent.exampleQuestions).toEqual(['First?', 'Second?'])
    expect(sent.demo).toBeNull()
    expect(sent.statement).toBeNull()
    expect(sent.pages).toEqual([{ slug: 'how-it-works', title: 'How it works', navHint: '', summary: '', body: '' }])
  })

  it('refuses a chapter line it cannot read, naming the line', async () => {
    const user = userEvent.setup()
    renderAt('/admin/portfolio/new')

    await user.type(await screen.findByLabelText('Name'), 'New')
    await user.type(screen.getByLabelText('Video address'), '/media/demo.mp4')
    await user.type(screen.getByLabelText('Chapters'), '0:00 Start{enter}soon Admin')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/Chapter line 2/)).toBeInTheDocument()
    expect(screen.getByLabelText('Chapters')).toHaveAttribute('aria-invalid', 'true')
    expect(createAdminPortfolioProject).not.toHaveBeenCalled()
  })

  it('shows the server field errors beside their inputs', async () => {
    const user = userEvent.setup()
    vi.mocked(createAdminPortfolioProject).mockRejectedValue(new AdminValidationError('bad', [
      { field: 'liveUrl', message: 'liveUrl must be an https:// address' },
    ]))
    renderAt('/admin/portfolio/new')
    await user.type(await screen.findByLabelText('Name'), 'X')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('https://')
    expect(screen.getByLabelText('Live link')).toHaveAttribute('aria-invalid', 'true')
  })
})
