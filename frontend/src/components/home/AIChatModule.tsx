import { useEffect, useRef, useState } from 'react'
import { ArrowRight, Zap } from 'lucide-react'
import * as chatService from '../../services/chatService'

interface Message {
  role: 'user' | 'assistant'
  content: string
}

const SUGGESTED_PROMPTS = [
  '"Tell me about his AI stack"',
  '"Leadership philosophy?"',
]

export function AIChatModule() {
  const [messages, setMessages] = useState<Message[]>([
    { role: 'assistant', content: 'Hello. I am Simon\'s digital architect assistant. How can I help you navigate his experience?' },
  ])
  const [input, setInput] = useState('')
  const [isStreaming, setIsStreaming] = useState(false)
  const [connectionFailed, setConnectionFailed] = useState(false)
  const sessionId = useRef(crypto.randomUUID())
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const connected = useRef(false)

  useEffect(() => {
    return () => {
      if (connected.current) {
        chatService.disconnect()
      }
    }
  }, [])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const ensureConnected = () => {
    if (!connected.current) {
      chatService.connect(
        sessionId.current,
        (response) => {
          if (response.type === 'STREAM_START') {
            setIsStreaming(true)
            setMessages(prev => [...prev, { role: 'assistant', content: '' }])
          } else if (response.type === 'STREAM_CHUNK') {
            setMessages(prev => {
              const updated = [...prev]
              const last = updated[updated.length - 1]
              if (last && last.role === 'assistant') {
                updated[updated.length - 1] = { ...last, content: last.content + (response.content ?? '') }
              }
              return updated
            })
          } else if (response.type === 'STREAM_END') {
            setIsStreaming(false)
          } else if (response.type === 'ERROR') {
            setIsStreaming(false)
          }
        },
        () => { connected.current = true; setConnectionFailed(false) },
        () => { connected.current = false; setConnectionFailed(true) }
      )
    }
  }

  const sendMessage = (text: string) => {
    if (!text.trim() || isStreaming) return
    ensureConnected()
    setMessages(prev => [...prev, { role: 'user', content: text.trim() }])
    setInput('')
    chatService.sendMessage({ sessionId: sessionId.current, message: text.trim() })
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    sendMessage(input)
  }

  const handlePromptClick = (prompt: string) => {
    const cleanPrompt = prompt.replace(/"/g, '')
    sendMessage(cleanPrompt)
  }

  return (
    <div className="chat-module">
      <div className="chat-module__inner">
        <div className="chat-module__header">
          <div className="chat-module__status">
            <span className="chat-module__dot" />
            <span className="label-sm">SIMONAI INTERFACE</span>
          </div>
          <Zap size={16} />
        </div>
        <div className="chat-module__body">
          <div className="chat-module__messages">
            {messages.map((msg, i) => (
              <div key={i} className={`chat-module__message chat-module__message--${msg.role}`}>
                <p>{msg.content}</p>
              </div>
            ))}
            {isStreaming && messages[messages.length - 1]?.content === '' && (
              <div className="chat-module__typing">
                <span /><span /><span />
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>
          {messages.length <= 1 && (
            <div className="chat-module__prompts">
              {SUGGESTED_PROMPTS.map(prompt => (
                <button
                  key={prompt}
                  className="chip"
                  onClick={() => handlePromptClick(prompt)}
                  type="button"
                >
                  {prompt}
                </button>
              ))}
            </div>
          )}
          {connectionFailed && (
            <div className="chat-module__fallback">
              <p>Chat is currently unavailable. Please try again later.</p>
            </div>
          )}
          <form className="chat-module__input-row" onSubmit={handleSubmit}>
            <input
              className="input-field chat-module__input"
              placeholder="Ask about expertise, projects, or hire..."
              value={input}
              onChange={e => setInput(e.target.value)}
              disabled={isStreaming}
              type="text"
            />
            <button className="chat-module__send" type="submit" disabled={isStreaming || !input.trim()}>
              <ArrowRight size={18} />
            </button>
          </form>
        </div>
      </div>
    </div>
  )
}
