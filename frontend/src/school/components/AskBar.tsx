import { MessageCircle, Send, Sparkles } from 'lucide-react'
import { useState, type FormEvent } from 'react'

interface AskBarProps {
  /** Sends a typed question. */
  onSend: (text: string) => void
  /** Sends one of the suggested questions. */
  onSuggestion: (text: string) => void
  suggestions: string[]
  /** True while an answer is still arriving. */
  disabled: boolean
  maxLength: number
  /** How many messages the conversation already holds; zero when there is none. */
  conversationLength: number
  /** Goes back to the conversation without asking anything. */
  onResume: () => void
}

/**
 * The one-line question box at the top of the page.
 *
 * Deliberately not the chat panel: the page leads with what is going on at school, and a
 * question is one line until it is asked. Asking switches the page to the conversation view,
 * where the full chat input takes over.
 */
export function AskBar({
  onSend,
  onSuggestion,
  suggestions,
  disabled,
  maxLength,
  conversationLength,
  onResume,
}: AskBarProps) {
  const [value, setValue] = useState('')

  function submit(event: FormEvent) {
    event.preventDefault()
    const trimmed = value.trim()
    if (!trimmed || disabled) return
    onSend(trimmed)
    setValue('')
  }

  return (
    <section className="school-ask" aria-label="Ask Term Time">
      <form className="school-ask__bar" onSubmit={submit}>
        <Sparkles size={20} className="school-ask__icon" aria-hidden="true" />
        <input
          type="text"
          className="school-ask__field"
          aria-label="Ask a question"
          placeholder="Ask anything about Kilmorie: dates, clubs, trips, letters…"
          value={value}
          maxLength={maxLength}
          disabled={disabled}
          onChange={(event) => setValue(event.target.value)}
        />
        <button
          type="submit"
          className="school-ask__send"
          aria-label="Send message"
          disabled={disabled || !value.trim()}
        >
          <Send size={18} />
        </button>
      </form>
      <div className="school-ask__chips">
        {conversationLength > 0 && (
          <button type="button" className="school-ask__resume" onClick={onResume}>
            <MessageCircle size={14} aria-hidden="true" />
            Back to your conversation
          </button>
        )}
        {suggestions.map((suggestion) => (
          <button
            key={suggestion}
            type="button"
            className="school-ask__chip"
            disabled={disabled}
            onClick={() => onSuggestion(suggestion)}
          >
            {suggestion}
          </button>
        ))}
      </div>
    </section>
  )
}
