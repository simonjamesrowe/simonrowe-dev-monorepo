interface ContactInfoProps {
  location: string
  phoneNumber: string
  primaryEmail: string
  secondaryEmail: string
}

export function ContactInfo({ location, phoneNumber, primaryEmail, secondaryEmail }: ContactInfoProps) {
  return (
    <div className="contact-info">
      <ul className="contact-info__list">
        <li className="contact-info__item">{location}</li>
        <li className="contact-info__item">
          <a href={`tel:${phoneNumber}`} aria-label={`Call ${phoneNumber}`}>
            {phoneNumber}
          </a>
        </li>
        <li className="contact-info__item">
          <span className="contact-info__label">Primary: </span>
          <a href={`mailto:${primaryEmail}`}>{primaryEmail}</a>
        </li>
        <li className="contact-info__item">
          <span className="contact-info__label">Secondary: </span>
          <a href={`mailto:${secondaryEmail}`}>{secondaryEmail}</a>
        </li>
      </ul>
    </div>
  )
}
