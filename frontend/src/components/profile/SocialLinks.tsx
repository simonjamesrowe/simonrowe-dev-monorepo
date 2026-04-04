import { Github, Linkedin, Twitter } from 'lucide-react'

import type { SocialMediaLink, SocialMediaPlatform } from '../../types/SocialMediaLink'

interface SocialLinksProps {
  links: SocialMediaLink[]
}

const platformIcons: Record<SocialMediaPlatform, React.ReactNode> = {
  github: <Github size={20} />,
  linkedin: <Linkedin size={20} />,
  twitter: <Twitter size={20} />,
}

const platformLabels: Record<SocialMediaPlatform, string> = {
  github: 'GitHub',
  linkedin: 'LinkedIn',
  twitter: 'Twitter',
}

export function SocialLinks({ links }: SocialLinksProps) {
  if (links.length === 0) {
    return null
  }

  return (
    <ul className="social-links">
      {links.map((link) => (
        <li key={`${link.type}-${link.url}`} className="social-links__item">
          <a
            href={link.url}
            rel="noopener noreferrer"
            target="_blank"
            aria-label={`${platformLabels[link.type] ?? link.name} profile`}
          >
            <span className="social-links__icon" aria-hidden="true">
              {platformIcons[link.type] ?? null}
            </span>
            <span className="social-links__details">
              <span className="social-links__platform">
                {platformLabels[link.type] ?? link.name}
              </span>
              <span className="social-links__url">{link.url}</span>
            </span>
          </a>
        </li>
      ))}
    </ul>
  )
}
