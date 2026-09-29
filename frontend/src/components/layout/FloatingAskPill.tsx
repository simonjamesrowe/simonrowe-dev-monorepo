import { Sparkles } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'

import { useChat } from '../../contexts/ChatContext'

/**
 * "Ask Simon anything", docked to the bottom-right corner of every public page; a sparkle
 * button at phone widths. It replaces the old floating "Take a Tour" button in that corner —
 * the tour now starts from the About menu.
 */
export function FloatingAskPill() {
  const { pathname } = useLocation()
  const { openChat } = useChat()
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    // A page with its own Ask affordance (`[data-ask-anchor]`, the home hero's) hides the pill
    // until that has scrolled out of view, so the two are never on screen together. With no
    // anchor the pill shows — but not straight away: the home hero renders only once the profile
    // has loaded, and deciding "no anchor" at once would flash the pill for that request's length.
    let settled = false
    const update = () => {
      const anchor = document.querySelector('[data-ask-anchor]')
      if (anchor) {
        setVisible(anchor.getBoundingClientRect().bottom < 0)
      } else if (settled) {
        setVisible(true)
      }
    }
    const firstCheck = window.setTimeout(() => {
      settled = true
      update()
    }, 700)
    update()
    window.addEventListener('scroll', update, { passive: true })
    window.addEventListener('resize', update)
    // The anchor mounts late — the home hero renders only once the profile has loaded — and
    // a scroll listener alone would leave the pill showing over it until the first scroll.
    const observer = new MutationObserver(update)
    observer.observe(document.body, { childList: true, subtree: true })
    return () => {
      window.clearTimeout(firstCheck)
      window.removeEventListener('scroll', update)
      window.removeEventListener('resize', update)
      observer.disconnect()
    }
  }, [pathname])

  return (
    <div className={`ask-pill${visible ? ' ask-pill--visible' : ''}`} aria-hidden={!visible}>
      <button
        aria-label="Ask Simon anything"
        className="ask-pill__button"
        onClick={() => openChat()}
        tabIndex={visible ? 0 : -1}
        type="button"
      >
        <Sparkles aria-hidden="true" size={17} />
        <span className="ask-pill__label">Ask Simon anything</span>
        <span className="ask-pill__start">Start chat</span>
      </button>
    </div>
  )
}
