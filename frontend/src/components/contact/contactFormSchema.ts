import { z } from 'zod'

export function buildContactFormSchema(recaptchaEnabled: boolean) {
  return z.object({
    firstName: z.string().min(1, 'First name is required').max(100),
    lastName: z.string().min(1, 'Last name is required').max(100),
    email: z.string().min(1, 'Email is required').email('Invalid email address').max(254),
    subject: z.string().min(1, 'Subject is required').max(200),
    message: z.string().min(1, 'Message is required').max(5000),
    recaptchaToken: recaptchaEnabled
      ? z.string().min(1, 'Please complete the reCAPTCHA verification')
      : z.string(),
  })
}

export const contactFormSchema = buildContactFormSchema(true)

export type ContactFormData = z.infer<typeof contactFormSchema>
