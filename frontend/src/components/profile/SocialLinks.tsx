import type { SocialMediaLink } from '../../types/SocialMediaLink'

interface SocialLinksProps {
  links: SocialMediaLink[]
  onSocialClick?: (type: string) => void
}

const iconByType: Record<string, string> = {
  github: 'GH',
  linkedin: 'IN',
  twitter: 'TW',
}

export function SocialLinks({ links, onSocialClick }: SocialLinksProps) {
  if (links.length === 0) {
    return null
  }

  return (
    <section className="panel" aria-label="Social media links">
      <h3>Social</h3>
      <ul className="social-links">
        {links.map((link) => (
          <li key={`${link.type}-${link.url}`}>
            <a
              href={link.url}
              onClick={() => onSocialClick?.(link.type)}
              rel="noopener noreferrer"
              target="_blank"
            >
              <span aria-hidden="true" className="social-links__icon" data-testid={`${link.type}-icon`}>
                {iconByType[link.type] ?? '::'}
              </span>
              <span>{link.name}</span>
            </a>
          </li>
        ))}
      </ul>
    </section>
  )
}
