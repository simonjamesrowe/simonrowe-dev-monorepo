import { Download, Github, Linkedin, Twitter } from 'lucide-react'

import { API_BASE_URL } from '../../config/api'
import type { SocialMediaLink } from '../../types/SocialMediaLink'

interface ConnectStripProps {
  socialMediaLinks?: SocialMediaLink[]
}

const socialIcons: Record<string, React.ReactNode> = {
  github: <Github size={20} />,
  linkedin: <Linkedin size={20} />,
  twitter: <Twitter size={20} />,
}

const socialLabels: Record<string, string> = {
  github: 'GitHub',
  linkedin: 'LinkedIn',
  twitter: 'Twitter',
}

export function ConnectStrip({ socialMediaLinks = [] }: ConnectStripProps) {
  const uniqueLinks = socialMediaLinks.filter(
    (link, index, arr) => arr.findIndex(l => l.type === link.type) === index,
  )

  return (
    <section className="connect-strip">
      <p className="connect-strip__eyebrow">Connect</p>
      <a
        className="button button--primary connect-strip__cv"
        href={`${API_BASE_URL}/api/resume`}
        rel="noopener noreferrer"
        target="_blank"
      >
        <Download size={16} /> Download CV
      </a>
      {uniqueLinks.length > 0 && (
        <ul className="connect-strip__socials">
          {uniqueLinks.map(link => (
            <li key={link.url}>
              <a
                aria-label={socialLabels[link.type] ?? link.name}
                className="connect-strip__social-link"
                href={link.url}
                rel="noopener noreferrer"
                target="_blank"
              >
                {socialIcons[link.type] ?? link.type}
              </a>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
