import { zodResolver } from '@hookform/resolvers/zod'
import ReCAPTCHA from 'react-google-recaptcha'
import { useRef, useState } from 'react'
import { useForm } from 'react-hook-form'

import { ContactApiError, submitContactForm } from '../../services/contactApi'
import { contactFormSchema, type ContactFormData } from './contactFormSchema'
import { FormField } from './FormField'

const RECAPTCHA_SITE_KEY = import.meta.env.VITE_RECAPTCHA_SITE_KEY as string

export function ContactForm() {
  const recaptchaRef = useRef<ReCAPTCHA>(null)
  const [submitSuccess, setSubmitSuccess] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    setValue,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ContactFormData>({
    resolver: zodResolver(contactFormSchema),
    defaultValues: {
      firstName: '',
      lastName: '',
      email: '',
      subject: '',
      message: '',
      recaptchaToken: '',
    },
  })

  const onRecaptchaChange = (token: string | null) => {
    setValue('recaptchaToken', token ?? '', { shouldValidate: true })
  }

  const onSubmit = async (data: ContactFormData) => {
    setServerError(null)
    try {
      await submitContactForm(data)
      setSubmitSuccess(true)
      reset()
      recaptchaRef.current?.reset()
    } catch (err) {
      if (err instanceof ContactApiError) {
        setServerError(err.message)
      } else {
        setServerError('An unexpected error occurred. Please try again later.')
      }
    }
  }

  if (submitSuccess) {
    return (
      <div className="contact-form__success" role="alert">
        <p>Thank you for your message. I&apos;ll be in touch soon!</p>
      </div>
    )
  }

  return (
    <form className="contact-form" onSubmit={handleSubmit(onSubmit)} noValidate>
      {serverError && (
        <div className="contact-form__error" role="alert">
          {serverError}
        </div>
      )}

      <div className="contact-form__row">
        <FormField
          id="firstName"
          label="First name"
          registration={register('firstName')}
          error={errors.firstName}
        />
        <FormField
          id="lastName"
          label="Last name"
          registration={register('lastName')}
          error={errors.lastName}
        />
      </div>

      <FormField
        id="email"
        label="Email address"
        type="email"
        registration={register('email')}
        error={errors.email}
      />

      <FormField
        id="subject"
        label="Subject"
        registration={register('subject')}
        error={errors.subject}
      />

      <FormField
        id="message"
        label="Message"
        type="textarea"
        registration={register('message')}
        error={errors.message}
      />

      <div className="contact-form__recaptcha">
        <ReCAPTCHA
          ref={recaptchaRef}
          sitekey={RECAPTCHA_SITE_KEY}
          onChange={onRecaptchaChange}
        />
        {errors.recaptchaToken && (
          <span className="form-field__error" role="alert">
            {errors.recaptchaToken.message}
          </span>
        )}
      </div>

      <button
        type="submit"
        className="button button--primary"
        disabled={isSubmitting}
      >
        {isSubmitting ? 'Sending...' : 'Send message'}
      </button>
    </form>
  )
}
