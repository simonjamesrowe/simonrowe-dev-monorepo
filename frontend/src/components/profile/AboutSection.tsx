import ReactMarkdown from 'react-markdown'
import rehypeSanitize from 'rehype-sanitize'
import { Github, Linkedin, Mail, MapPin, Phone, Twitter } from 'lucide-react'

import type { SocialMediaLink } from '../../types/SocialMediaLink'

interface AboutSectionProps {
  description: string
  profileImageUrl: string
  profileName: string
  location: string
  primaryEmail: string
  secondaryEmail?: string
  phoneNumber: string
  socialLinks?: SocialMediaLink[]
  onSocialClick?: (type: string) => void
}

const socialIcons: Record<string, React.ReactNode> = {
  github: <Github size={16} />,
  linkedin: <Linkedin size={16} />,
  twitter: <Twitter size={16} />,
}

export function AboutSection({
  description,
  profileImageUrl,
  profileName,
  location,
  primaryEmail,
  secondaryEmail,
  phoneNumber,
  socialLinks,
  onSocialClick,
}: AboutSectionProps) {
  return (
    <section className="panel about-section tour-about" id="about">
      <div className="about-section__image-column">
        <img
          alt={profileName}
          className="about-section__image"
          src={profileImageUrl}
        />
      </div>
      <div className="about-section__content-column">
        <h3>About</h3>
        <ReactMarkdown
          rehypePlugins={[rehypeSanitize]}
          components={{
            a: ({ href, children }) => (
              <a href={href} target="_blank" rel="noopener noreferrer">
                {children}
              </a>
            ),
          }}
        >
          {description}
        </ReactMarkdown>

        <hr className="about-section__divider" />

        <ul className="about-section__contact">
          <li>
            <MapPin size={16} />
            <span>{location}</span>
          </li>
          <li>
            <Mail size={16} />
            <a href={`mailto:${primaryEmail}`}>{primaryEmail}</a>
          </li>
          {secondaryEmail ? (
            <li>
              <Mail size={16} />
              <a href={`mailto:${secondaryEmail}`}>{secondaryEmail}</a>
            </li>
          ) : null}
          <li>
            <Phone size={16} />
            <a href={`tel:${phoneNumber}`}>{phoneNumber}</a>
          </li>
        </ul>

        {socialLinks && socialLinks.length > 0 && (
          <div className="about-section__social">
            {socialLinks.map((link) => (
              <a
                key={`${link.type}-${link.url}`}
                className="about-section__social-link"
                href={link.url}
                onClick={() => onSocialClick?.(link.type)}
                rel="noopener noreferrer"
                target="_blank"
              >
                {socialIcons[link.type] ?? null}
                <span>{link.name}</span>
              </a>
            ))}
          </div>
        )}
      </div>
    </section>
  )
}
