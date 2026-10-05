import type { ServiceCommit } from '../../services/softwareFactoryApi'

/**
 * The commit a redeploy targets: the newest one any deployed service was built from.
 *
 * Mirrors the backend's `RedeployTarget`, which is the boundary — this copy exists only so the
 * console can name the commit, and the confirmation phrase, before the request is made. A
 * disagreement between the two is refused by the server, never deployed.
 *
 * Publish rebuilds only the images whose code changed and tags the rest with the new commit,
 * so the services routinely report different commits. At the newest of them every service's
 * image is the one running now; an older one would roll back whichever was built after it.
 * Order matters on equal times, as on the server: backend, then frontend, then software-factory.
 *
 * @returns the target, or null when any of the three commits is unknown
 */
export function redeployTarget(
  serverSide: ServiceCommit[],
  frontendCommit: string,
  frontendCommitTime: string | null,
): ServiceCommit | null {
  const backend = serverSide.find((entry) => entry.service === 'backend')
  const factory = serverSide.find((entry) => entry.service === 'software-factory')
  if (!backend || !factory || frontendCommit === 'unknown' || !frontendCommitTime) {
    return null
  }
  const candidates: ServiceCommit[] = [
    backend,
    { service: 'frontend', commit: frontendCommit, commitTime: frontendCommitTime },
    factory,
  ]
  let newest = candidates[0]
  for (const candidate of candidates) {
    if (Date.parse(candidate.commitTime) > Date.parse(newest.commitTime)) {
      newest = candidate
    }
  }
  return newest
}
