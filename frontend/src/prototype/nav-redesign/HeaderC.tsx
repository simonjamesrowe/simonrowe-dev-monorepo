/**
 * PROTOTYPE — Variant C: ask-first. The header's centre is one command bar (search and chat
 * merged, reusing SiteSearch), and every menu lives behind a single "Menu" button that opens a
 * full-screen typographic index — the same on desktop and mobile.
 */
import { ArrowRight, Compass, LayoutGrid, Moon, Sparkles, Sun, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'

import { useChat } from '../../contexts/ChatContext'
import { useTheme } from '../../contexts/ThemeContext'
import { useTour } from '../../hooks/useTour'
import { SiteSearch } from '../../components/search/SiteSearch'
import { NAV_GROUPS } from './data'

export function HeaderC() {
  const [indexOpen, setIndexOpen] = useState(false)
  const [question, setQuestion] = useState('')
  const location = useLocation()
  const { openChat } = useChat()
  const { theme, toggleTheme } = useTheme()
  const { start: startTour } = useTour()

  useEffect(() => setIndexOpen(false), [location.pathname, location.hash])

  useEffect(() => {
    document.body.style.overflow = indexOpen ? 'hidden' : ''
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setIndexOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = ''
      window.removeEventListener('keydown', onKey)
    }
  }, [indexOpen])

  const ask = (event: React.FormEvent) => {
    event.preventDefault()
    if (!question.trim()) return
    setIndexOpen(false)
    openChat(question.trim())
    setQuestion('')
  }

  return (
    <>
      <header className="pc-header">
        <Link to="/" className="top-nav__brand" aria-label="Simon Rowe homepage">
          <span className="top-nav__brand-mark">SR</span>
          <span className="top-nav__brand-name">Simon Rowe</span>
        </Link>
        <div className="pc-command">
          <Sparkles className="pc-command__spark" size={16} />
          <SiteSearch onChatStart={openChat} />
        </div>
        <div className="pc-actions">
          <button aria-label="Ask Simon anything" className="proto-spark-btn pc-mobile-only" onClick={() => openChat()} type="button">
            <Sparkles size={18} />
          </button>
          <button
            aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            className="nav__theme-toggle pc-desktop-only"
            onClick={toggleTheme}
            type="button"
          >
            {theme === 'dark' ? <Sun size={19} /> : <Moon size={19} />}
          </button>
          <button aria-expanded={indexOpen} className="pc-menu-btn" onClick={() => setIndexOpen(true)} type="button">
            <LayoutGrid size={17} /> <span>Menu</span>
          </button>
        </div>
      </header>

      {indexOpen ? (
        <div className="pc-index" role="dialog" aria-label="Site index">
          <div className="pc-index__top">
            <Link to="/" className="top-nav__brand" aria-label="Simon Rowe homepage">
              <span className="top-nav__brand-mark">SR</span>
              <span className="top-nav__brand-name">Simon Rowe</span>
            </Link>
            <button className="pc-menu-btn" onClick={() => setIndexOpen(false)} type="button">
              <X size={17} /> <span>Close</span>
            </button>
          </div>

          <form className="pc-index__ask" onSubmit={ask}>
            <Sparkles size={20} />
            <input
              aria-label="Ask Simon anything"
              onChange={event => setQuestion(event.target.value)}
              placeholder="Ask Simon anything…"
              value={question}
            />
            <button aria-label="Send" type="submit"><ArrowRight size={18} /></button>
          </form>

          <div className="pc-index__grid">
            {NAV_GROUPS.map((group, index) => (
              <section className="pc-index__col" key={group.key}>
                <p className="pc-index__num">0{index + 1} &mdash; {group.label}</p>
                <ul>
                  {group.items.map(item => (
                    <li key={item.label}>
                      <Link className="pc-index__link" to={item.to}>
                        {item.label}
                        {item.comingSoon ? <span className="proto-badge proto-badge--inline">Soon</span> : null}
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>

          <div className="pc-index__foot">
            <button className="pa-sheet__row" onClick={() => { setIndexOpen(false); void startTour() }} type="button">
              <Compass size={16} /> Take the guided tour
            </button>
            <button className="pa-sheet__row" onClick={toggleTheme} type="button">
              {theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
              {theme === 'dark' ? 'Light mode' : 'Dark mode'}
            </button>
          </div>
        </div>
      ) : null}
    </>
  )
}
