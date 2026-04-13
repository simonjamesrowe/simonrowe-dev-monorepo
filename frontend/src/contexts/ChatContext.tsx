import { createContext, useCallback, useContext, useState } from 'react'
import type { ReactNode } from 'react'

interface ChatContextValue {
  chatOpen: boolean
  chatQuery: string | null
  recaptchaVerified: boolean
  showRecaptcha: boolean
  openChat: (query?: string) => void
  closeChat: () => void
  handleRecaptchaVerified: () => void
  cancelRecaptcha: () => void
  openChatBypassRecaptcha: (query?: string) => void
}

const ChatContext = createContext<ChatContextValue | null>(null)

export function ChatProvider({ children }: { children: ReactNode }) {
  const [chatOpen, setChatOpen] = useState(false)
  const [chatQuery, setChatQuery] = useState<string | null>(null)
  const [recaptchaVerified, setRecaptchaVerified] = useState(false)
  const [showRecaptcha, setShowRecaptcha] = useState(false)

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
  }, [])

  const handleRecaptchaVerified = useCallback(() => {
    setRecaptchaVerified(true)
    setShowRecaptcha(false)
    setChatOpen(true)
  }, [])

  const cancelRecaptcha = useCallback(() => {
    setShowRecaptcha(false)
    setChatQuery(null)
  }, [])

  /** Opens chat bypassing reCAPTCHA — for use by the site tour only */
  const openChatBypassRecaptcha = useCallback((query?: string) => {
    setRecaptchaVerified(true)
    setShowRecaptcha(false)
    setChatQuery(query ?? null)
    setChatOpen(true)
  }, [])

  return (
    <ChatContext.Provider value={{
      chatOpen,
      chatQuery,
      recaptchaVerified,
      showRecaptcha,
      openChat,
      closeChat,
      handleRecaptchaVerified,
      cancelRecaptcha,
      openChatBypassRecaptcha,
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
