import ReCAPTCHA from 'react-google-recaptcha'
import { useEffect, useRef, useState } from 'react'
import { ShieldCheck, X } from 'lucide-react'
import { API_BASE_URL } from '../../config/api'

interface RecaptchaGateProps {
  onVerified: () => void
  onCancel: () => void
}

function getRecaptchaSiteKey(): string {
  return (import.meta.env.VITE_RECAPTCHA_SITE_KEY as string) || ''
}

export function RecaptchaGate({ onVerified, onCancel }: RecaptchaGateProps) {
  const recaptchaRef = useRef<ReCAPTCHA>(null)
  const [error, setError] = useState<string | null>(null)
  const [verifying, setVerifying] = useState(false)
  const siteKey = getRecaptchaSiteKey()

  useEffect(() => {
    if (!siteKey) {
      onVerified()
    }
  }, [siteKey, onVerified])

  const handleRecaptchaChange = async (token: string | null) => {
    if (!token) return

    setVerifying(true)
    setError(null)

    try {
      const response = await fetch(`${API_BASE_URL}/api/recaptcha/verify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }),
      })

      if (response.ok) {
        onVerified()
      } else {
        setError('Verification failed. Please try again.')
        recaptchaRef.current?.reset()
      }
    } catch {
      setError('Verification service unavailable. Please try again.')
      recaptchaRef.current?.reset()
    } finally {
      setVerifying(false)
    }
  }

  if (!siteKey) {
    return null
  }

  return (
    <div className="recaptcha-gate__backdrop" onClick={onCancel}>
      <div className="recaptcha-gate" onClick={(e) => e.stopPropagation()}>
        <button
          className="recaptcha-gate__close"
          onClick={onCancel}
          aria-label="Cancel"
          type="button"
        >
          <X size={18} />
        </button>
        <div className="recaptcha-gate__icon">
          <ShieldCheck size={28} />
        </div>
        <p className="recaptcha-gate__title">Quick verification</p>
        <p className="recaptcha-gate__subtitle">
          Please complete the check below to start chatting.
        </p>
        <div className="recaptcha-gate__widget">
          <ReCAPTCHA
            ref={recaptchaRef}
            sitekey={siteKey}
            onChange={handleRecaptchaChange}
          />
        </div>
        {verifying && (
          <p className="recaptcha-gate__status">Verifying...</p>
        )}
        {error && (
          <p className="recaptcha-gate__error">{error}</p>
        )}
      </div>
    </div>
  )
}
