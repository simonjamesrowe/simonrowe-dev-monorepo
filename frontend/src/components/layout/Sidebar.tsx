import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Briefcase, ChevronsLeft, Code, Github, Linkedin, Mail, PenLine, Twitter, User } from 'lucide-react'

import type { SocialMediaLink } from '../../types/SocialMediaLink'

export interface NavigationItem {
  id: string
  label: string
  route?: string
}

interface SidebarProps {
  aboutImageUrl: string
  items: NavigationItem[]
  onNavigate?: (section: string) => void
  socialLinks?: SocialMediaLink[]
  onSocialClick?: (type: string) => void
}

const navIcons: Record<string, React.ReactNode> = {
  about: <User size={18} />,
  experience: <Briefcase size={18} />,
  skills: <Code size={18} />,
  blog: <PenLine size={18} />,
  contact: <Mail size={18} />,
}

const socialIcons: Record<string, React.ReactNode> = {
  github: <Github size={18} />,
  linkedin: <Linkedin size={18} />,
  twitter: <Twitter size={18} />,
}

export function Sidebar({ aboutImageUrl, items, onNavigate, socialLinks, onSocialClick }: SidebarProps) {
  const [collapsed, setCollapsed] = useState(true)
  const navigate = useNavigate()

  const navigateTo = (item: NavigationItem) => {
    if (item.route) {
      void navigate(item.route)
    } else {
      const el = document.getElementById(item.id)
      if (el) {
        el.scrollIntoView({ behavior: 'smooth' })
      } else {
        void navigate(`/#${item.id}`)
      }
    }
    onNavigate?.(item.id)
  }

  return (
    <aside className={`sidebar${collapsed ? ' sidebar--collapsed' : ''}`} aria-label="Desktop navigation">
      <nav>
        <ul>
          {items.map((item) => (
            <li key={item.id}>
              <button onClick={() => navigateTo(item)} title={item.label} type="button">
                {item.id === 'profile' ? (
                  <img alt="Profile" className="sidebar__about-icon" src={aboutImageUrl} />
                ) : (
                  <span aria-hidden="true" className="sidebar__icon" data-testid={`${item.id}-icon`}>
                    {navIcons[item.id] ?? null}
                  </span>
                )}
                <span className="sidebar__label">{item.label}</span>
              </button>
            </li>
          ))}
        </ul>
      </nav>
      {socialLinks && socialLinks.length > 0 && (
        <div className="sidebar__social-links">
          <hr className="sidebar__divider" />
          <ul>
            {socialLinks.map((link) => (
              <li key={`${link.type}-${link.url}`}>
                <a
                  className="sidebar__social-item"
                  href={link.url}
                  onClick={() => onSocialClick?.(link.type)}
                  rel="noopener noreferrer"
                  target="_blank"
                  title={link.name}
                >
                  <span aria-hidden="true" className="sidebar__icon">
                    {socialIcons[link.type] ?? null}
                  </span>
                  <span className="sidebar__label">{link.name}</span>
                </a>
              </li>
            ))}
          </ul>
        </div>
      )}
      <button
        aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        className="sidebar__toggle"
        onClick={() => setCollapsed((v) => !v)}
        type="button"
      >
        <ChevronsLeft size={16} />
      </button>
    </aside>
  )
}
