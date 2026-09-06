import { createContext, useCallback, useContext, useState } from 'react'
import type { ReactNode } from 'react'

interface ChatContextValue {
  chatOpen: boolean
  chatQuery: string | null
  recaptchaVerified: boolean
  showRecaptcha: boolean
  tourChatAwaitingResponse: boolean
  openChat: (query?: string) => void
  closeChat: () => void
  handleRecaptchaVerified: () => void
  cancelRecaptcha: () => void
  openChatBypassRecaptcha: (query?: string) => void
  completeTourChatResponse: () => void
}

const ChatContext = createContext<ChatContextValue | null>(null)

export function ChatProvider({ children }: { children: ReactNode }) {
  const [chatOpen, setChatOpen] = useState(false)
  const [chatQuery, setChatQuery] = useState<string | null>(null)
  const [recaptchaVerified, setRecaptchaVerified] = useState(false)
  const [showRecaptcha, setShowRecaptcha] = useState(false)
  const [tourChatAwaitingResponse, setTourChatAwaitingResponse] = useState(false)

  const openChat = useCallback((query?: string) => {
    setChatQuery(query ?? null)
    if (recaptchaVerified) {
      setChatOpen(true)
    } else {
      setShowRecaptcha(true)
    }
  }, [recaptchaVerified])

  const closeChat = useCallback(() => {
    setChatOpen(false)
    setChatQuery(null)
    setTourChatAwaitingResponse(false)
  }, [])

  const handleRecaptchaVerified = useCallback(() => {
    setRecaptchaVerified(true)
    setShowRecaptcha(false)
    setChatOpen(true)
  }, [])

  const cancelRecaptcha = useCallback(() => {
    setShowRecaptcha(false)
    setChatQuery(null)
    setTourChatAwaitingResponse(false)
  }, [])

  /** Opens chat bypassing reCAPTCHA — for use by the site tour only */
  const openChatBypassRecaptcha = useCallback((query?: string) => {
    setRecaptchaVerified(true)
    setShowRecaptcha(false)
    setChatQuery(query ?? null)
    setChatOpen(true)
    setTourChatAwaitingResponse(true)
  }, [])

  const completeTourChatResponse = useCallback(() => {
    setTourChatAwaitingResponse(false)
  }, [])

  return (
    <ChatContext.Provider value={{
      chatOpen,
      chatQuery,
      recaptchaVerified,
      showRecaptcha,
      tourChatAwaitingResponse,
      openChat,
      closeChat,
      handleRecaptchaVerified,
      cancelRecaptcha,
      openChatBypassRecaptcha,
      completeTourChatResponse,
    }}>
      {children}
    </ChatContext.Provider>
  )
}

export function useChat() {
  const context = useContext(ChatContext)
  if (!context) {
    throw new Error('useChat must be used within a ChatProvider')
  }
  return context
}
