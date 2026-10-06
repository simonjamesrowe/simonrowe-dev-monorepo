import type { ServiceVersion } from '../../types/platform'

interface DriftWarningProps {
  services: ServiceVersion[]
}

/** The two services that run the same image, so any difference between them is drift. */
const SAME_IMAGE = ['software-factory', 'deployer']

/**
 * Warns when `software-factory` and `deployer` are on different commits.
 *
 * It used to warn whenever any first-party services differed. Publish now rebuilds only the
 * images whose code changed, so the backend, the frontend and the factory routinely report
 * different commits after a perfectly good deploy, and that warning would have shown on almost
 * every page view. The pair that still has to match is these two: they run one image, and the
 * deployer never recreates itself, so a difference means it was left behind — the case this
 * warning existed for.
 *
 * Services that are not reporting are excluded rather than counted as drift: "unknown" is
 * not evidence of a mismatch.
 */
export function DriftWarning({ services }: DriftWarningProps) {
  const pair = services.filter(
    (s) => SAME_IMAGE.includes(s.name) && s.reachable && s.commit !== 'unknown',
  )
  const commits = new Set(pair.map((s) => s.commit))

  if (pair.length < 2 || commits.size <= 1) {
    return null
  }

  return (
    <p className="status-page__drift" role="alert">
      <strong>software-factory and deployer run the same image but different commits</strong>:{' '}
      {pair.map((s) => `${s.name} (${s.shortCommit})`).join(', ')}. The deployer does not update
      itself, so it needs recreating by hand.
    </p>
  )
}
