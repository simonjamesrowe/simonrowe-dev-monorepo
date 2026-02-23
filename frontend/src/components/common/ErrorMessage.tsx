interface ErrorMessageProps {
  message: string
  onRetry?: () => void
}

export function ErrorMessage({ message, onRetry }: ErrorMessageProps) {
  return (
    <div className="error-state" role="alert">
      <h2>Unable to load homepage</h2>
      <p>{message}</p>
      {onRetry ? (
        <button className="button button--secondary" onClick={onRetry} type="button">
          Retry
        </button>
      ) : null}
    </div>
  )
}
