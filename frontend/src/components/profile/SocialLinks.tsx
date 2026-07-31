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

/**
 * A link's own name when it has one, else a label derived from its platform.
 *
 * The site has two GitHub links; preferring the configured name is what keeps them
 * distinguishable instead of both reading "GitHub".
 */
function labelFor(link: SocialMediaLink): string {
  const name = link.name?.trim()
  return name !== undefined && name !== '' ? name : platformLabels[link.type]
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
            aria-label={`${labelFor(link)} profile`}
          >
            <span className="social-links__icon" aria-hidden="true">
              {platformIcons[link.type] ?? null}
            </span>
            {/* The label is the whole link text; the raw URL is deliberately not shown —
                it added a wrapping second line per entry and said nothing useful. */}
            <span className="social-links__platform">{labelFor(link)}</span>
          </a>
        </li>
      ))}
    </ul>
  )
}
