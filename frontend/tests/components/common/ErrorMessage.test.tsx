import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ErrorMessage } from '../../../src/components/common/ErrorMessage'

describe('ErrorMessage', () => {
  it('uses a page-neutral default heading', () => {
    render(<ErrorMessage message="Unable to load blog data." />)

    expect(screen.getByRole('heading')).toHaveTextContent('Something went wrong')
    // The old hardcoded heading appeared on every page, including non-home pages.
    expect(screen.queryByText('Unable to load homepage')).not.toBeInTheDocument()
  })

  it('uses a caller-supplied heading', () => {
    render(<ErrorMessage message="Unable to load blog data." title="Unable to load the blog" />)

    expect(screen.getByRole('heading')).toHaveTextContent('Unable to load the blog')
  })

  it('renders the message inside an alert region', () => {
    render(<ErrorMessage message="Unable to load blog data." />)

    expect(screen.getByRole('alert')).toHaveTextContent('Unable to load blog data.')
  })

  it('omits the retry button when no handler is given', () => {
    render(<ErrorMessage message="Nope." />)

    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })

  it('invokes the retry handler when the button is pressed', async () => {
    const onRetry = vi.fn()
    render(<ErrorMessage message="Nope." onRetry={onRetry} />)

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))

    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
