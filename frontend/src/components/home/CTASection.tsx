import { Link } from 'react-router-dom'

interface CTASectionProps {
  onContact: () => void
}

export function CTASection({ onContact }: CTASectionProps) {
  return (
    <section className="cta-section tour-contact">
      <h2 className="cta-section__heading headline-lg">
        Let's build the <em className="cta-section__emphasis">impossible</em> together.
      </h2>
      <div className="cta-section__actions">
        <button type="button" className="cta-section__btn-primary" onClick={onContact}>
          Get In Touch
        </button>
        <Link to="/experience" className="cta-section__btn-secondary">
          Explore Work
        </Link>
      </div>
    </section>
  )
}
