export function LoadingIndicator() {
  return (
    <div className="loading-state" role="status" aria-live="polite">
      <div className="loading-state__spinner" aria-hidden="true" />
      <p>Loading profile...</p>
    </div>
  )
}
