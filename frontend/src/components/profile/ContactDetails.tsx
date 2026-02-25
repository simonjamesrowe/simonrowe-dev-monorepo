import { useState } from 'react'

interface ContactDetailsProps {
  location: string
  primaryEmail: string
  secondaryEmail?: string
  phoneNumber: string
  onToggle?: (expanded: boolean) => void
}

export function ContactDetails({
  location,
  primaryEmail,
  secondaryEmail,
  phoneNumber,
  onToggle,
}: ContactDetailsProps) {
  const [expanded, setExpanded] = useState(false)

  const toggle = () => {
    const next = !expanded
    setExpanded(next)
    onToggle?.(next)
  }

  return (
    <section className="panel tour-contact" id="contact">
      <div className="contact-details__header">
        <h3>Contact</h3>
        <button
          aria-expanded={expanded}
          className="button button--secondary"
          onClick={toggle}
          type="button"
        >
          {expanded ? 'Hide details' : 'Show details'}
        </button>
      </div>

      <div className={`contact-details ${expanded ? 'is-expanded' : ''}`}>
        {expanded ? (
          <ul>
            <li>{location}</li>
            <li>
              <a href={`mailto:${primaryEmail}`}>{primaryEmail}</a>
            </li>
            {secondaryEmail ? (
              <li>
                <a href={`mailto:${secondaryEmail}`}>{secondaryEmail}</a>
              </li>
            ) : null}
            <li>
              <a href={`tel:${phoneNumber}`}>{phoneNumber}</a>
            </li>
          </ul>
        ) : null}
      </div>
    </section>
  )
}
