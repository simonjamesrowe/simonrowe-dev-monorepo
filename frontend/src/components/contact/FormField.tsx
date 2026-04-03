import type { FieldError } from 'react-hook-form'

interface FormFieldProps {
  id: string
  label: string
  type?: 'text' | 'email' | 'textarea'
  registration: Record<string, unknown>
  error?: FieldError
}

export function FormField({ id, label, type = 'text', registration, error }: FormFieldProps) {
  return (
    <div className="form-field">
      <label className="form-field__label" htmlFor={id}>{label}</label>
      {type === 'textarea' ? (
        <textarea className="form-field__textarea" id={id} {...registration} />
      ) : (
        <input className="form-field__input" id={id} type={type} {...registration} />
      )}
      {error && (
        <span className="form-field__error" role="alert">
          {error.message}
        </span>
      )}
    </div>
  )
}
