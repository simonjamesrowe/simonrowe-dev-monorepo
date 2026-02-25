import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { MarkdownEditor } from '../../src/components/admin/MarkdownEditor'

describe('MarkdownEditor', () => {
  let onChange: ReturnType<typeof vi.fn>

  beforeEach(() => {
    onChange = vi.fn()
  })

  it('renders textarea with initial value', () => {
    render(<MarkdownEditor value="Hello world" onChange={onChange} />)

    const textarea = screen.getByRole('textbox')
    expect(textarea).toBeInTheDocument()
    expect(textarea).toHaveValue('Hello world')
  })

  it('calls onChange when textarea content changes', () => {
    render(<MarkdownEditor value="" onChange={onChange} />)

    const textarea = screen.getByRole('textbox')
    fireEvent.change(textarea, { target: { value: 'New content' } })

    expect(onChange).toHaveBeenCalledWith('New content')
  })

  it('shows Write tab active by default', () => {
    render(<MarkdownEditor value="Some content" onChange={onChange} />)

    const writeTab = screen.getByRole('button', { name: 'Write' })
    expect(writeTab).toBeInTheDocument()
    expect(writeTab.className).toContain('md-editor__tab--active')
  })

  it('toolbar buttons are visible in write mode', () => {
    render(<MarkdownEditor value="" onChange={onChange} />)

    expect(screen.getByTitle('Bold')).toBeInTheDocument()
    expect(screen.getByTitle('Italic')).toBeInTheDocument()
    expect(screen.getByTitle('Heading')).toBeInTheDocument()
    expect(screen.getByTitle('Link')).toBeInTheDocument()
    expect(screen.getByTitle('Inline code')).toBeInTheDocument()
    expect(screen.getByTitle('Image')).toBeInTheDocument()
  })

  it('switches to Preview tab and shows rendered markdown content', () => {
    render(<MarkdownEditor value="# My Heading" onChange={onChange} />)

    const previewTab = screen.getByRole('button', { name: 'Preview' })
    fireEvent.click(previewTab)

    expect(previewTab.className).toContain('md-editor__tab--active')
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'My Heading' })).toBeInTheDocument()
  })

  it('shows empty preview message when content is blank', () => {
    render(<MarkdownEditor value="" onChange={onChange} />)

    const previewTab = screen.getByRole('button', { name: 'Preview' })
    fireEvent.click(previewTab)

    expect(screen.getByText('Nothing to preview.')).toBeInTheDocument()
  })

  it('switching back from Preview to Write tab restores textarea', () => {
    render(<MarkdownEditor value="Some text" onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: 'Preview' }))
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Write' }))
    expect(screen.getByRole('textbox')).toBeInTheDocument()
  })

  it('renders placeholder text on textarea', () => {
    render(
      <MarkdownEditor value="" onChange={onChange} placeholder="Enter markdown here..." />,
    )

    expect(screen.getByPlaceholderText('Enter markdown here...')).toBeInTheDocument()
  })
})
