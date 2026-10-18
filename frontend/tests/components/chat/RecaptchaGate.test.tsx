import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { RecaptchaGate } from '../../../src/components/chat/RecaptchaGate'

// The real widget loads Google's script; the gate's own behaviour is what is under test.
vi.mock('react-google-recaptcha', async () => {
  const React = await import('react')
  return {
    default: React.forwardRef(function MockReCAPTCHA() {
      return React.createElement('div', { 'data-testid': 'recaptcha-widget' })
    }),
  }
})

describe('RecaptchaGate', () => {
  const fetchSpy = vi.fn()

  beforeEach(() => {
    vi.stubEnv('VITE_RECAPTCHA_SITE_KEY', 'test-site-key')
    // Nothing here should verify a token; fail loudly rather than reach the live API.
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    fetchSpy.mockReset()
  })

  function renderGate() {
    const onVerified = vi.fn()
    const onCancel = vi.fn()
    const view = render(<RecaptchaGate onCancel={onCancel} onVerified={onVerified} />)
    return { ...view, onVerified, onCancel }
  }

  it('stays open when the click lands inside the panel, on the widget included', () => {
    const { onCancel } = renderGate()

    fireEvent.click(screen.getByTestId('recaptcha-widget'))
    fireEvent.click(screen.getByText('Quick verification'))

    expect(onCancel).not.toHaveBeenCalled()
  })

  it('cancels on a click on the backdrop', () => {
    const { container, onCancel, onVerified } = renderGate()

    fireEvent.click(container.querySelector('.recaptcha-gate__backdrop')!)

    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onVerified).not.toHaveBeenCalled()
  })

  it('cancels from its Cancel button', () => {
    const { onCancel } = renderGate()

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('passes straight through, rendering nothing, when no site key is configured', () => {
    vi.stubEnv('VITE_RECAPTCHA_SITE_KEY', '')
    const { container, onVerified } = renderGate()

    expect(container).toBeEmptyDOMElement()
    expect(onVerified).toHaveBeenCalledTimes(1)
  })
})
