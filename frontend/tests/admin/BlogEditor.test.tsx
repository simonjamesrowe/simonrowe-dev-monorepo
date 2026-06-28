import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { BlogEditor } from '../../src/pages/admin/BlogEditor'

vi.mock('@mdxeditor/editor', async () => {
  const React = await import('react')
  return {
    MDXEditor: React.forwardRef(function MockMDXEditor(
      { markdown, onChange }: { markdown: string; onChange?: (val: string) => void },
      ref: React.Ref<{ getMarkdown: () => string; insertMarkdown: () => void }>,
    ) {
      const valueRef = React.useRef(markdown)
      React.useImperativeHandle(ref, () => ({
        getMarkdown: () => valueRef.current,
        insertMarkdown: () => {},
        setMarkdown: (v: string) => { valueRef.current = v },
        focus: () => {},
      }))
      return React.createElement('textarea', {
        'data-testid': 'mdx-editor',
        value: valueRef.current,
        onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => {
          valueRef.current = e.target.value
          onChange?.(e.target.value)
        },
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
  createAdminBlog: vi.fn(),
  updateAdminBlog: vi.fn(),
  fetchAdminBlogById: vi.fn(),
  fetchAdminTags: vi.fn(),
  fetchAdminSkills: vi.fn(),
  uploadAdminMedia: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

vi.mock('../../src/hooks/useUnsavedChanges', () => ({
  useUnsavedChanges: vi.fn(),
}))

import {
  createAdminBlog,
  fetchAdminBlogById,
  fetchAdminTags,
  fetchAdminSkills,
} from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const mockCreateAdminBlog = vi.mocked(createAdminBlog)
const mockFetchAdminBlogById = vi.mocked(fetchAdminBlogById)
const mockFetchAdminTags = vi.mocked(fetchAdminTags)
const mockFetchAdminSkills = vi.mocked(fetchAdminSkills)
const mockUseAuth = vi.mocked(useAuth)

const mockGetAccessToken = vi.fn().mockResolvedValue('test-token')

function renderNewBlog() {
  return render(
    <MemoryRouter initialEntries={['/admin/blogs/new']}>
      <Routes>
        <Route element={<BlogEditor />} path="/admin/blogs/:id" />
      </Routes>
    </MemoryRouter>,
  )
}

function renderEditBlog(id: string) {
  return render(
    <MemoryRouter initialEntries={[`/admin/blogs/${id}`]}>
      <Routes>
        <Route element={<BlogEditor />} path="/admin/blogs/:id" />
      </Routes>
    </MemoryRouter>,
  )
}

function getTitleInput() {
  return document.querySelector('input[type="text"]') as HTMLInputElement
}

function getShortDescriptionTextarea() {
  return document.querySelectorAll('textarea')[0] as HTMLTextAreaElement
}

function getPublishedCheckbox() {
  return document.querySelector('input[type="checkbox"]') as HTMLInputElement
}

describe('BlogEditor', () => {
  beforeEach(() => {
    mockUseAuth.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: undefined,
      login: vi.fn(),
      logout: vi.fn(),
      getAccessToken: mockGetAccessToken,
    })
    mockFetchAdminTags.mockResolvedValue([])
    mockFetchAdminSkills.mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 0,
      size: 100,
      number: 0,
    })
    mockCreateAdminBlog.mockReset()
    mockFetchAdminBlogById.mockReset()
  })

  it('renders form fields for a new blog', async () => {
    renderNewBlog()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'New Blog' })).toBeInTheDocument()
    })

    expect(screen.getByText('Title')).toBeInTheDocument()
    expect(screen.getByText('Short Description')).toBeInTheDocument()
    expect(screen.getByText('Content')).toBeInTheDocument()
    expect(screen.getByText('Draft')).toBeInTheDocument()

    expect(document.querySelector('input[type="text"]')).toBeInTheDocument()
    expect(document.querySelector('input[type="checkbox"]')).toBeInTheDocument()
  })

  it('renders save and cancel buttons', async () => {
    renderNewBlog()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('save button calls createAdminBlog for a new blog', async () => {
    mockCreateAdminBlog.mockResolvedValue({
      id: 'new-id',
      title: 'Test Blog',
      shortDescription: 'A description',
      content: '',
      published: false,
      featuredImage: null,
      tags: [],
      skills: [],
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z',
    })

    renderNewBlog()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'New Blog' })).toBeInTheDocument()
    })

    const titleInput = getTitleInput()
    const shortDescTextarea = getShortDescriptionTextarea()

    fireEvent.change(titleInput, { target: { value: 'Test Blog' } })
    fireEvent.change(shortDescTextarea, { target: { value: 'A description' } })

    fireEvent.submit(screen.getByRole('button', { name: 'Save' }).closest('form')!)

    await waitFor(() => {
      expect(mockCreateAdminBlog).toHaveBeenCalledWith(
        mockGetAccessToken,
        expect.objectContaining({ title: 'Test Blog', shortDescription: 'A description' }),
      )
    })
  })

  it('loads existing blog data when id param is present', async () => {
    mockFetchAdminBlogById.mockResolvedValue({
      id: 'blog-123',
      title: 'Existing Blog Title',
      shortDescription: 'Existing description',
      content: '# Content',
      published: true,
      featuredImage: '/images/featured.jpg',
      tags: [],
      skills: [],
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z',
    })

    renderEditBlog('blog-123')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Edit Blog' })).toBeInTheDocument()
    })

    expect(getTitleInput()).toHaveValue('Existing Blog Title')
    expect(getShortDescriptionTextarea()).toHaveValue('Existing description')
    expect(getPublishedCheckbox()).toBeChecked()
  })

  it('shows loading state while fetching existing blog', () => {
    mockFetchAdminBlogById.mockImplementation(() => new Promise(() => {}))

    renderEditBlog('blog-123')

    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })
})
