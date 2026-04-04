import { useEffect } from 'react'
import { X } from 'lucide-react'
import { ContactForm } from './ContactForm'

interface ContactDrawerProps {
  open: boolean
  onClose: () => void
}

export function ContactDrawer({ open, onClose }: ContactDrawerProps) {
  useEffect(() => {
    if (open) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => { document.body.style.overflow = '' }
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
      <div className={`contact-drawer${open ? ' contact-drawer--open' : ''}`}>
        <div className="contact-drawer__header">
          <h2 className="contact-drawer__title">Get In Touch</h2>
          <button type="button" className="contact-drawer__close" onClick={onClose} aria-label="Close">
            <X size={20} />
          </button>
        </div>
        <div className="contact-drawer__body">
          <p className="contact-drawer__subtext">
            Whether you have a project in mind, a question, or just want to talk
            architecture — reach out. I read every message.
          </p>
          <ContactForm />
        </div>
      </div>
    </>
  )
}
