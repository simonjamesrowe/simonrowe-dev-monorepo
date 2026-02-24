import { ContactForm } from './ContactForm'
import { ContactInfo } from './ContactInfo'

const CONTACT_INFO = {
  location: 'London, UK',
  phoneNumber: '+44 7000 000000',
  primaryEmail: 'simon@simonjamesrowe.com',
  secondaryEmail: 'simon.rowe@gmail.com',
}

export function ContactSection() {
  return (
    <section className="contact-section" id="contact">
      <h2>Contact</h2>
      <div className="contact-section__layout">
        <div className="contact-section__form">
          <ContactForm />
        </div>
        <div className="contact-section__info">
          <ContactInfo
            location={CONTACT_INFO.location}
            phoneNumber={CONTACT_INFO.phoneNumber}
            primaryEmail={CONTACT_INFO.primaryEmail}
            secondaryEmail={CONTACT_INFO.secondaryEmail}
          />
        </div>
      </div>
    </section>
  )
}
