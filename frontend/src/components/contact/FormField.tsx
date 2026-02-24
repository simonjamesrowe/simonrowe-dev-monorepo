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
      <label htmlFor={id}>{label}</label>
      {type === 'textarea' ? (
        <textarea id={id} {...registration} />
      ) : (
        <input id={id} type={type} {...registration} />
      )}
      {error && (
        <span className="form-field__error" role="alert">
          {error.message}
        </span>
      )}
    </div>
  )
}
