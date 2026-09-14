import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ProfileAdmin } from '../../src/pages/admin/ProfileAdmin'

vi.mock('@mdxeditor/editor', async () => {
  const React = await import('react')
  return {
    MDXEditor: React.forwardRef(function MockMdxEditor(
      { markdown }: { markdown: string },
      ref: React.Ref<{ getMarkdown: () => string }>,
    ) {
      React.useImperativeHandle(ref, () => ({ getMarkdown: () => markdown }))
      return React.createElement('textarea', {
        'data-testid': 'mdx-editor',
        defaultValue: markdown,
      })
    }),
    headingsPlugin: () => ({}),
    listsPlugin: () => ({}),
    quotePlugin: () => ({}),
    thematicBreakPlugin: () => ({}),
    linkPlugin: () => ({}),
    linkDialogPlugin: () => ({}),
    imagePlugin: () => ({}),
    codeBlockPlugin: () => ({}),
    codeMirrorPlugin: () => ({}),
    markdownShortcutPlugin: () => ({}),
    toolbarPlugin: () => ({}),
    BoldItalicUnderlineToggles: () => null,
    BlockTypeSelect: () => null,
    CreateLink: () => null,
    InsertImage: () => null,
    InsertCodeBlock: () => null,
    ListsToggle: () => null,
    CodeToggle: () => null,
  }
})

vi.mock('../../src/services/adminApi', () => ({
  fetchAdminProfile: vi.fn(),
  updateAdminProfile: vi.fn(),
  fetchAdminSocialMedia: vi.fn(),
  createAdminSocialMedia: vi.fn(),
  updateAdminSocialMedia: vi.fn(),
  deleteAdminSocialMedia: vi.fn(),
  uploadAdminMedia: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({ useAuth: vi.fn() }))

vi.mock('../../src/hooks/useUnsavedChanges', () => ({
  useUnsavedChanges: vi.fn(),
}))

import {
  fetchAdminProfile,
  fetchAdminSocialMedia,
  updateAdminProfile,
} from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const mockFetchAdminProfile = vi.mocked(fetchAdminProfile)
const mockFetchAdminSocialMedia = vi.mocked(fetchAdminSocialMedia)
const mockUpdateAdminProfile = vi.mocked(updateAdminProfile)
const mockUseAuth = vi.mocked(useAuth)

const profile = {
  id: 'p-1',
  name: 'Simon Rowe',
  title: 'Software Engineering Leader',
  headline: 'A headline',
  description: 'A description',
  location: 'London',
  phoneNumber: '+44123456',
  primaryEmail: 'simon@example.com',
  secondaryEmail: null,
  profileImage: null,
  sidebarImage: null,
  backgroundImage: null,
  mobileBackgroundImage: null,
  resumeSummary: 'The existing CV summary.',
  resumePhoto: { url: '/uploads/abc/headshot.jpg' },
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

describe('ProfileAdmin CV fields', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseAuth.mockReturnValue({
      getAccessToken: vi.fn().mockResolvedValue('test-token'),
    } as unknown as ReturnType<typeof useAuth>)
    mockFetchAdminProfile.mockResolvedValue(profile)
    mockFetchAdminSocialMedia.mockResolvedValue([])
    mockUpdateAdminProfile.mockResolvedValue(profile)
  })

  async function renderProfileAdmin() {
    render(
      <MemoryRouter>
        <ProfileAdmin />
      </MemoryRouter>,
    )
    return screen.findByLabelText('Summary')
  }

  it('loads the existing CV summary into the form', async () => {
    const summary = await renderProfileAdmin()

    expect(summary).toHaveValue('The existing CV summary.')
  })

  it('saves an edited summary', async () => {
    const summary = await renderProfileAdmin()

    fireEvent.change(summary, { target: { value: 'A rewritten summary.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save Profile' }))

    await waitFor(() => expect(mockUpdateAdminProfile).toHaveBeenCalled())
    expect(mockUpdateAdminProfile.mock.calls[0][1]).toMatchObject({
      resumeSummary: 'A rewritten summary.',
    })
  })

  it('carries the CV photo through a save that does not touch it', async () => {
    // The admin PUT rebuilds the whole profile document, so a field the form forgets to
    // send is a field the save silently clears.
    await renderProfileAdmin()

    fireEvent.click(screen.getByRole('button', { name: 'Save Profile' }))

    await waitFor(() => expect(mockUpdateAdminProfile).toHaveBeenCalled())
    expect(mockUpdateAdminProfile.mock.calls[0][1]).toMatchObject({
      resumePhoto: { url: '/uploads/abc/headshot.jpg' },
    })
  })

  it('shows how much of the summary budget is used', async () => {
    await renderProfileAdmin()

    expect(screen.getByText(/24 \/ 900/)).toBeInTheDocument()
  })
})
