interface ErrorMessageProps {
  message: string
  /** Heading for the error frame. Defaults to a page-neutral line. */
  title?: string
  onRetry?: () => void
}

export function ErrorMessage({
  message,
  title = 'Something went wrong',
  onRetry,
}: ErrorMessageProps) {
  return (
    <div className="error-state" role="alert">
      <h2>{title}</h2>
      <p>{message}</p>
      {onRetry ? (
        <button className="button button--secondary" onClick={onRetry} type="button">
          Retry
        </button>
      ) : null}
    </div>
  )
}
