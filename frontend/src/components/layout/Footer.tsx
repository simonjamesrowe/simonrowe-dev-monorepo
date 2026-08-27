import { Download, Github, Linkedin, Mail, Twitter } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

import { API_BASE_URL } from '../../config/api'
import { useProfile } from '../../hooks/useProfile'
import { VersionBadge } from './VersionBadge'

const SOCIAL_ICONS: Record<string, ReactNode> = {
  github: <Github size={18} />,
  linkedin: <Linkedin size={18} />,
  twitter: <Twitter size={18} />,
}

const SOCIAL_LABELS: Record<string, string> = {
  github: 'GitHub',
  linkedin: 'LinkedIn',
  twitter: 'Twitter',
}

const FALLBACK_NAME = 'Simon Rowe'

/**
 * A single-bar site footer: copyright, the connect icons, and one way to get in touch.
 *
 * Deliberately minimal. It previously carried a brand block, a positioning statement and
 * a six-link nav column, which made it taller than some of the pages it sat under — the
 * nav duplicated the top navigation that is present on every page anyway.
 *
 * NOTE: this is a conscious narrowing of FR-010, which asked the footer to link to every
 * public section. See `spec.md` — the requirement was revised after seeing it rendered.
 *
 * Icons are icon-only but each keeps an `aria-label` and a `title`, so the accessible
 * name and the hover tooltip carry the full text — including the two GitHub accounts,
 * which is the whole point of naming them distinctly.
 *
 * It calls `useProfile()` itself and deliberately ignores that hook's `loading` and
 * `error`: the copyright and the contact link are static and must appear immediately, and
 * a failed profile fetch must never put an error frame at the bottom of every page. The
 * profile-dependent icons simply appear when the data arrives.
 */
export function Footer() {
  const { profile } = useProfile()

  const name = profile?.name ?? FALLBACK_NAME
  const email = profile?.primaryEmail
  // De-duplicated by URL rather than by platform: there are two GitHub accounts with
  // distinct names, and collapsing by platform would silently hide one of them.
  const socialLinks = (profile?.socialMediaLinks ?? []).filter(
    (link, index, all) => all.findIndex((other) => other.url === link.url) === index,
  )

  return (
    <footer className="footer">
      <div className="footer__bar">
        <p className="footer__copyright">
          &copy; {new Date().getFullYear()} {name}
        </p>

        <VersionBadge />

        <ul className="footer__icons">
          {socialLinks.map((link) => {
            const label = link.name?.trim() || SOCIAL_LABELS[link.type] || link.type
            return (
              <li key={link.url}>
                <a
                  aria-label={label}
                  className="footer__icon-link"
                  href={link.url}
                  rel="noopener noreferrer"
                  target="_blank"
                  title={label}
                >
                  {SOCIAL_ICONS[link.type] ?? <span aria-hidden="true">{label.charAt(0)}</span>}
                </a>
              </li>
            )
          })}
          {email ? (
            <li>
              <a
                aria-label={`Email ${email}`}
                className="footer__icon-link"
                href={`mailto:${email}`}
                title={email}
              >
                <Mail size={18} />
              </a>
            </li>
          ) : null}
          <li>
            <a
              aria-label="Download CV"
              className="footer__icon-link"
              href={`${API_BASE_URL}/api/resume`}
              rel="noopener noreferrer"
              target="_blank"
              title="Download CV"
            >
              <Download size={18} />
            </a>
          </li>
        </ul>

        {/* ProfilePage opens its contact drawer when the hash is #contact, so this
            lands straight on the form rather than scrolling the page. */}
        <Link className="footer__contact-link" to="/profile#contact">
          Get in touch
        </Link>
      </div>
    </footer>
  )
}
