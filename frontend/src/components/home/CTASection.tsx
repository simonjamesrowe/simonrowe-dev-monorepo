import { Download } from 'lucide-react'
import { Link } from 'react-router-dom'

import { API_BASE_URL } from '../../config/api'

/**
 * The contact call-to-action band that closes the home page (FR-008).
 *
 * The root keeps `tour-contact`: the site tour has a step targeting that class, which
 * until this section was revived had no element on the home page.
 *
 * The CV link points at `/api/resume`, which generates the CV, rather than at
 * `profile.cvUrl`.
 *
 * Uses the shared `.button` classes rather than the bespoke `.cta-section__btn-*`
 * pair it used to carry: those reimplemented a primary button with `--on-surface`,
 * so they bypassed the site's primary-button treatment entirely (FR-033).
 */
export function CTASection() {
  return (
    <section className="cta-section tour-contact">
      <h2 className="cta-section__heading headline-lg">
        Let&apos;s build the <em className="cta-section__emphasis">impossible</em> together.
      </h2>
      <div className="cta-section__actions">
        <Link className="button button--primary button--lg" to="/profile#contact">
          Get in touch
        </Link>
        <a
          className="button button--secondary button--lg"
          href={`${API_BASE_URL}/api/resume`}
          rel="noopener noreferrer"
          target="_blank"
        >
          <Download size={16} /> Download CV
        </a>
      </div>
    </section>
  )
}
