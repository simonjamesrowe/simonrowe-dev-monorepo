import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ChatPanel } from '../../../src/components/chat/ChatPanel'

vi.mock('../../../src/services/chatService', () => ({
  connect: vi.fn(),
  disconnect: vi.fn(),
  sendMessage: vi.fn(),
  isConnected: vi.fn(() => false),
}))

import * as chatService from '../../../src/services/chatService'

const mockCrypto = {
  randomUUID: vi.fn(() => 'test-session-uuid'),
}
vi.stubGlobal('crypto', mockCrypto)

// jsdom does not implement scrollIntoView
Element.prototype.scrollIntoView = vi.fn()

describe('ChatPanel', () => {
  const defaultProps = {
    initialQuery: 'Tell me about Simon',
    onClose: vi.fn(),
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders chat panel with header', () => {
    render(<ChatPanel {...defaultProps} />)

    expect(screen.getByText('Ask me anything')).toBeInTheDocument()
  })

  it('displays initial query as first user message', () => {
    render(<ChatPanel {...defaultProps} />)

    expect(screen.getByText('Tell me about Simon')).toBeInTheDocument()
  })

  it('generates a UUID session ID and connects via chatService', () => {
    render(<ChatPanel {...defaultProps} />)

    expect(chatService.connect).toHaveBeenCalledWith(
      'test-session-uuid',
      expect.any(Function),
      expect.any(Function),
      expect.any(Function),
    )
  })

  it('calls onClose when close button is clicked', async () => {
    const user = userEvent.setup()
    render(<ChatPanel {...defaultProps} />)

    const closeButton = screen.getByLabelText('Close chat')
    await user.click(closeButton)

    expect(defaultProps.onClose).toHaveBeenCalledTimes(1)
  })

  it('disconnects chatService on unmount', () => {
    const { unmount } = render(<ChatPanel {...defaultProps} />)

    unmount()

    expect(chatService.disconnect).toHaveBeenCalled()
  })

  it('sends initial message when connected', () => {
    vi.mocked(chatService.connect).mockImplementation(
      (_sessionId, _onMessage, onConnect) => {
        onConnect?.()
      },
    )

    render(<ChatPanel {...defaultProps} />)

    expect(chatService.sendMessage).toHaveBeenCalledWith({
      sessionId: 'test-session-uuid',
      message: 'Tell me about Simon',
    })
  })

  it('renders chat input area', () => {
    render(<ChatPanel {...defaultProps} />)

    expect(screen.getByPlaceholderText('Type a message...')).toBeInTheDocument()
  })
})
