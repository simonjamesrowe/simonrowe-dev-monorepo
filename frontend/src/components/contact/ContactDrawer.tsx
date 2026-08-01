import { useEffect } from 'react'
import { Download, X } from 'lucide-react'

import { ContactForm } from './ContactForm'
import { SocialLinks } from '../profile/SocialLinks'
import { API_BASE_URL } from '../../config/api'
import type { SocialMediaLink } from '../../types/SocialMediaLink'

interface ContactDrawerProps {
  open: boolean
  onClose: () => void
  /** Path to the CV asset; falls back to the generated resume endpoint. */
  cvUrl?: string | null
  socialMediaLinks?: SocialMediaLink[]
}

/**
 * The right-side "Get in touch" drawer: the contact form plus the CV download and social
 * links that used to occupy a full-width Connect section on the profile page.
 *
 * Replacing that section with a drawer keeps the profile page about the profile, and
 * means every "Get in touch" on the site resolves to the same one place.
 */
export function ContactDrawer({
  open,
  onClose,
  cvUrl,
  socialMediaLinks = [],
}: ContactDrawerProps) {
  useEffect(() => {
    if (open) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [open])

  useEffect(() => {
    const handleEsc = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && open) onClose()
    }
    document.addEventListener('keydown', handleEsc)
    return () => document.removeEventListener('keydown', handleEsc)
  }, [open, onClose])

  return (
    <>
      <div
        className={`contact-drawer-overlay${open ? ' contact-drawer-overlay--open' : ''}`}
        onClick={onClose}
      />
      <div
        aria-hidden={open ? undefined : 'true'}
        aria-label="Get in touch"
        className={`contact-drawer${open ? ' contact-drawer--open' : ''}`}
        role="dialog"
      >
        <div className="contact-drawer__header">
          <h2 className="contact-drawer__title">Get in touch</h2>
          <button
            aria-label="Close"
            className="contact-drawer__close"
            onClick={onClose}
            type="button"
          >
            <X size={20} />
          </button>
        </div>
        <div className="contact-drawer__body">
          <p className="contact-drawer__subtext">
            Whether you have a project in mind, a question, or just want to talk
            architecture &mdash; reach out. I read every message.
          </p>
          <ContactForm />
          <div className="contact-drawer__extras">
            {/* Rendered with the social-links markup rather than as a button: it belongs
                to the same list of ways to reach things, and the form's own Send message
                button is the only action in here that should read as one. */}
            <ul className="social-links">
              <li className="social-links__item">
                <a
                  href={`${API_BASE_URL}${cvUrl ?? '/api/resume'}`}
                  rel="noopener noreferrer"
                  target="_blank"
                >
                  <span aria-hidden="true" className="social-links__icon">
                    <Download size={20} />
                  </span>
                  <span className="social-links__platform">Download CV</span>
                </a>
              </li>
            </ul>
            <SocialLinks links={socialMediaLinks} />
          </div>
        </div>
      </div>
    </>
  )
}
