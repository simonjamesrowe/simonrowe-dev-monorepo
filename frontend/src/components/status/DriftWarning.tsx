import type { ServiceVersion } from '../../types/platform'

interface DriftWarningProps {
  services: ServiceVersion[]
}

/**
 * Warns when the first-party services are not all on the same commit.
 *
 * This is the most valuable single thing the page can say. A partial deploy — frontend
 * updated, backend not, or `deployer` left behind because it excludes itself from its own
 * recreate list — is a real and recurring state here, and once went unnoticed for months.
 *
 * Services that are not reporting are excluded rather than counted as drift: "unknown" is
 * not evidence of a mismatch.
 */
export function DriftWarning({ services }: DriftWarningProps) {
  const known = services.filter((s) => s.reachable && s.commit !== 'unknown')
  const commits = new Set(known.map((s) => s.commit))

  if (commits.size <= 1) {
    return null
  }

  return (
    <p className="status-page__drift" role="alert">
      These services are running <strong>different commits</strong>:{' '}
      {known.map((s) => `${s.name} (${s.shortCommit})`).join(', ')}. That usually means a
      partial deploy.
    </p>
  )
}
