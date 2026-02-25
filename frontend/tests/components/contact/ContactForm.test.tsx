import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'

import { ContactForm } from '../../../src/components/contact/ContactForm'
import { ContactApiError } from '../../../src/services/contactApi'

vi.mock('react-google-recaptcha', () => ({
  default: vi.fn(({ onChange }: { onChange: (token: string) => void }) => (
    <button type="button" onClick={() => onChange('mock-token')}>
      Complete reCAPTCHA
    </button>
  )),
}))

vi.mock('../../../src/services/contactApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../src/services/contactApi')>()
  return {
    ...actual,
    submitContactForm: vi.fn(),
  }
})

import { submitContactForm } from '../../../src/services/contactApi'
const mockSubmit = vi.mocked(submitContactForm)

describe('ContactForm', () => {
  beforeEach(() => {
    mockSubmit.mockReset()
    vi.stubEnv('VITE_RECAPTCHA_SITE_KEY', 'test-site-key')
  })

  it('renders all five form fields and reCAPTCHA', () => {
    render(<ContactForm />)

    expect(screen.getByLabelText('First name')).toBeInTheDocument()
    expect(screen.getByLabelText('Last name')).toBeInTheDocument()
    expect(screen.getByLabelText('Email address')).toBeInTheDocument()
    expect(screen.getByLabelText('Subject')).toBeInTheDocument()
    expect(screen.getByLabelText('Message')).toBeInTheDocument()
    expect(screen.getByText('Complete reCAPTCHA')).toBeInTheDocument()
  })

  it('shows validation errors when submitting empty form', async () => {
    render(<ContactForm />)
    await userEvent.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => {
      expect(screen.getByText('First name is required')).toBeInTheDocument()
      expect(screen.getByText('Last name is required')).toBeInTheDocument()
      expect(screen.getByText('Email is required')).toBeInTheDocument()
      expect(screen.getByText('Subject is required')).toBeInTheDocument()
      expect(screen.getByText('Message is required')).toBeInTheDocument()
    })
  })

  it('shows email format error for invalid email', async () => {
    render(<ContactForm />)

    await userEvent.type(screen.getByLabelText('Email address'), 'not-an-email')
    await userEvent.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => {
      expect(screen.getByText('Invalid email address')).toBeInTheDocument()
    })
  })

  it('submits successfully and displays success message', async () => {
    mockSubmit.mockResolvedValue(undefined)

    render(<ContactForm />)

    await userEvent.type(screen.getByLabelText('First name'), 'Jane')
    await userEvent.type(screen.getByLabelText('Last name'), 'Doe')
    await userEvent.type(screen.getByLabelText('Email address'), 'jane@example.com')
    await userEvent.type(screen.getByLabelText('Subject'), 'Hello')
    await userEvent.type(screen.getByLabelText('Message'), 'Test message content')
    await userEvent.click(screen.getByText('Complete reCAPTCHA'))
    await userEvent.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => {
      expect(
        screen.getByText("Thank you for your message. I'll be in touch soon!"),
      ).toBeInTheDocument()
    })
  })

  it('displays server error message on 500', async () => {
    mockSubmit.mockRejectedValue(
      new ContactApiError('Failed to send message. Please try again later.', undefined, 500),
    )

    render(<ContactForm />)

    await userEvent.type(screen.getByLabelText('First name'), 'Jane')
    await userEvent.type(screen.getByLabelText('Last name'), 'Doe')
    await userEvent.type(screen.getByLabelText('Email address'), 'jane@example.com')
    await userEvent.type(screen.getByLabelText('Subject'), 'Hello')
    await userEvent.type(screen.getByLabelText('Message'), 'Test message content')
    await userEvent.click(screen.getByText('Complete reCAPTCHA'))
    await userEvent.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => {
      expect(
        screen.getByText('Failed to send message. Please try again later.'),
      ).toBeInTheDocument()
    })
  })

  it('displays reCAPTCHA error when token missing', async () => {
    render(<ContactForm />)

    await userEvent.type(screen.getByLabelText('First name'), 'Jane')
    await userEvent.type(screen.getByLabelText('Last name'), 'Doe')
    await userEvent.type(screen.getByLabelText('Email address'), 'jane@example.com')
    await userEvent.type(screen.getByLabelText('Subject'), 'Hello')
    await userEvent.type(screen.getByLabelText('Message'), 'Test message content')
    // Do NOT click Complete reCAPTCHA
    await userEvent.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => {
      expect(
        screen.getByText('Please complete the reCAPTCHA verification'),
      ).toBeInTheDocument()
    })
  })

  it('resets form after successful submission', async () => {
    mockSubmit.mockResolvedValue(undefined)

    render(<ContactForm />)

    await userEvent.type(screen.getByLabelText('First name'), 'Jane')
    await userEvent.type(screen.getByLabelText('Last name'), 'Doe')
    await userEvent.type(screen.getByLabelText('Email address'), 'jane@example.com')
    await userEvent.type(screen.getByLabelText('Subject'), 'Hello')
    await userEvent.type(screen.getByLabelText('Message'), 'Test message content')
    await userEvent.click(screen.getByText('Complete reCAPTCHA'))
    await userEvent.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => {
      expect(
        screen.getByText("Thank you for your message. I'll be in touch soon!"),
      ).toBeInTheDocument()
    })

    // Form is no longer visible (replaced by success message)
    expect(screen.queryByLabelText('First name')).not.toBeInTheDocument()
  })
})
