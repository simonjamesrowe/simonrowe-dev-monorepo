/**
 * PROTOTYPE — Variant B: a full-width bar that sits transparent over the hero and turns solid
 * on scroll, with wide mega-menu panels carrying real content (latest posts, portfolio
 * silhouettes). On mobile the menus move to a bottom tab bar with bottom sheets.
 */
import { ArrowUpRight, ChevronDown, Ellipsis, House, LayoutGrid, Moon, Search, Sparkles, Sun, UserRound, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'

import { API_BASE_URL } from '../../config/api'
import { useChat } from '../../contexts/ChatContext'
import { useTheme } from '../../contexts/ThemeContext'
import { useProfile } from '../../hooks/useProfile'
import { SiteSearch } from '../../components/search/SiteSearch'
import { fetchLatestBlogs } from '../../services/blogApi'
import type { BlogSummary } from '../../types/blog'
import { NAV_GROUPS, PORTFOLIO, PORTFOLIO_ROUTE, groupIsActive, type NavGroup } from './data'
import { Silhouette } from './Silhouette'

export function HeaderB() {
  const [openGroup, setOpenGroup] = useState<string | null>(null)
  const [searchOpen, setSearchOpen] = useState(false)
  const [sheet, setSheet] = useState<string | null>(null)
  const [scrolled, setScrolled] = useState(false)
  const [posts, setPosts] = useState<BlogSummary[]>([])
  const headerRef = useRef<HTMLElement>(null)
  const location = useLocation()
  const { openChat } = useChat()
  const { theme, toggleTheme } = useTheme()
  const { profile } = useProfile()

  useEffect(() => {
    setOpenGroup(null)
    setSheet(null)
    setSearchOpen(false)
  }, [location.pathname, location.hash])

  useEffect(() => {
    void fetchLatestBlogs(2, 'ENGINEERING').then(setPosts).catch(() => setPosts([]))
    const onScroll = () => setScrolled(window.scrollY > 24)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpenGroup(null)
        setSheet(null)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('keydown', onKey)
    }
  }, [])

  const solid = scrolled || openGroup !== null || searchOpen || location.pathname !== '/'

  const featured = (group: NavGroup) => {
    switch (group.key) {
      case 'about':
        return (
          <div className="pb-feature pb-feature--about">
            {profile?.profileImage?.url ? (
              <img alt="" className="pb-feature__avatar" src={`${API_BASE_URL}${profile.profileImage.url}`} />
            ) : null}
            <div>
              <p className="pb-feature__title">{profile?.name ?? 'Simon Rowe'}</p>
              <p className="pb-feature__sub">{profile?.title}</p>
              <div className="pb-feature__chips">
                <button className="hero__prompt-chip" onClick={() => openChat("How big are the teams he's led?")} type="button">
                  How big are the teams he&rsquo;s led?
                </button>
                <button className="hero__prompt-chip" onClick={() => openChat('What is he building right now?')} type="button">
                  What is he building right now?
                </button>
              </div>
            </div>
          </div>
        )
      case 'portfolio':
        return (
          <div className="pb-feature pb-feature--portfolio">
            {PORTFOLIO.map(project => (
              <Link className="pb-feature__project" key={project.slug} to={PORTFOLIO_ROUTE}>
                <Silhouette project={project} size="sm" />
                <span className="pb-feature__project-name">{project.name}</span>
                <span className="pb-feature__project-tag">{project.tagline}</span>
              </Link>
            ))}
          </div>
        )
      case 'insights':
        return (
          <div className="pb-feature pb-feature--posts">
            <p className="pb-feature__eyebrow">Latest writing</p>
            {posts.map(post => (
              <Link className="pb-feature__post" key={post.id} to={`/blogs/${post.id}`}>
                {post.featuredImageUrl ? <img alt="" src={`${API_BASE_URL}${post.featuredImageUrl}`} /> : <span className="pb-feature__post-ph" />}
                <span>{post.title}</span>
              </Link>
            ))}
          </div>
        )
      default:
        return (
          <div className="pb-feature pb-feature--mcp">
            <p className="pb-feature__eyebrow">Connect your assistant</p>
            <code className="pb-feature__code">https://api.simonrowe.dev/mcp</code>
            <p className="pb-feature__sub">Ten tools: profile, jobs, skills, blogs, news, events and search.</p>
          </div>
        )
    }
  }

  return (
    <>
      <header
        className={`pb-header${solid ? ' is-solid' : ''}`}
        onMouseLeave={() => setOpenGroup(null)}
        ref={headerRef}
      >
        <div className="pb-bar">
          <Link to="/" className="top-nav__brand" aria-label="Simon Rowe homepage">
            <span className="top-nav__brand-mark">SR</span>
            <span className="top-nav__brand-name">Simon Rowe</span>
          </Link>
          <ul className="pb-menus">
            {NAV_GROUPS.map(group => (
              <li key={group.key} onMouseEnter={() => setOpenGroup(group.key)}>
                <button
                  aria-expanded={openGroup === group.key}
                  className={`pb-menus__trigger${groupIsActive(group, location.pathname) ? ' is-active' : ''}${openGroup === group.key ? ' is-open' : ''}`}
                  onClick={() => setOpenGroup(value => (value === group.key ? null : group.key))}
                  type="button"
                >
                  {group.label} <ChevronDown size={14} />
                </button>
              </li>
            ))}
          </ul>
          <div className="pb-actions">
            <button aria-label="Search" className="nav__theme-toggle" onClick={() => setSearchOpen(value => !value)} type="button">
              <Search size={19} />
            </button>
            <button
              aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
              className="nav__theme-toggle"
              onClick={toggleTheme}
              type="button"
            >
              {theme === 'dark' ? <Sun size={19} /> : <Moon size={19} />}
            </button>
            <button className="proto-ask-cta pb-desktop-only" onClick={() => openChat()} type="button">
              <Sparkles size={16} /> Ask Simon anything
            </button>
          </div>
        </div>

        {openGroup ? (() => {
          const group = NAV_GROUPS.find(candidate => candidate.key === openGroup)!
          return (
            <div className="pb-mega">
              <div className="pb-mega__inner">
                <div className="pb-mega__links">
                  <p className="pb-mega__heading">{group.label}</p>
                  <ul>
                    {group.items.map(item => {
                      const Icon = item.icon
                      return (
                        <li key={item.label}>
                          <Link className="pb-mega__link" to={item.to}>
                            <Icon size={18} />
                            <span>
                              <strong>
                                {item.label}
                                {item.comingSoon ? <span className="proto-badge proto-badge--inline">Soon</span> : null}
                              </strong>
                              <small>{item.description}</small>
                            </span>
                          </Link>
                        </li>
                      )
                    })}
                  </ul>
                  {group.overview ? (
                    <Link className="pb-mega__all" to={group.overview.to}>
                      {group.overview.label} <ArrowUpRight size={14} />
                    </Link>
                  ) : null}
                </div>
                {featured(group)}
              </div>
            </div>
          )
        })() : null}

        {searchOpen ? (
          <div className="pb-search"><SiteSearch onChatStart={openChat} /></div>
        ) : null}
      </header>

      <nav className="pb-tabbar" aria-label="Mobile navigation">
        <Link className={`pb-tab${location.pathname === '/' ? ' is-active' : ''}`} to="/"><House size={20} /><span>Home</span></Link>
        <button className={`pb-tab${groupIsActive(NAV_GROUPS[0], location.pathname) ? ' is-active' : ''}`} onClick={() => setSheet('about')} type="button"><UserRound size={20} /><span>About</span></button>
        <button className="pb-tab pb-tab--ask" onClick={() => openChat()} type="button"><Sparkles size={22} /><span>Ask</span></button>
        <button className={`pb-tab${groupIsActive(NAV_GROUPS[1], location.pathname) ? ' is-active' : ''}`} onClick={() => setSheet('portfolio')} type="button"><LayoutGrid size={20} /><span>Portfolio</span></button>
        <button className="pb-tab" onClick={() => setSheet('more')} type="button"><Ellipsis size={20} /><span>More</span></button>
      </nav>

      {sheet ? (
        <>
          <div className="pb-sheet-backdrop" onClick={() => setSheet(null)} />
          <div className="pb-sheet" role="dialog" aria-label="Menu">
            <div className="pb-sheet__grab" />
            <button aria-label="Close" className="nav__theme-toggle pb-sheet__close" onClick={() => setSheet(null)} type="button"><X size={20} /></button>
            {(sheet === 'more' ? NAV_GROUPS.slice(2) : NAV_GROUPS.filter(group => group.key === sheet)).map(group => (
              <div key={group.key}>
                <p className="pb-mega__heading">{group.label}</p>
                {group.key === 'portfolio' ? (
                  <div className="pb-sheet__projects">
                    {PORTFOLIO.map(project => (
                      <Link className="pb-feature__project" key={project.slug} to={PORTFOLIO_ROUTE}>
                        <Silhouette project={project} size="sm" />
                        <span className="pb-feature__project-name">{project.name}</span>
                      </Link>
                    ))}
                  </div>
                ) : (
                  <ul className="pb-sheet__list">
                    {group.items.map(item => {
                      const Icon = item.icon
                      return (
                        <li key={item.label}>
                          <Link className="pb-mega__link" to={item.to}>
                            <Icon size={18} />
                            <span><strong>{item.label}</strong><small>{item.description}</small></span>
                          </Link>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </div>
            ))}
          </div>
        </>
      ) : null}
    </>
  )
}
