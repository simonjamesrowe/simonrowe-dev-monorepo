/**
 * Mirrors the backend's `ReleaseSummaryStatus` enum. `PENDING` means "no paragraph yet";
 * `FAILED` means there never will be one for this release.
 */
export type ReleaseSummaryStatus = 'PENDING' | 'READY' | 'FAILED'

/**
 * One first-party service's version. Every field but `name` and `reachable` may be absent:
 * a service built outside a git checkout has no commit, and an unreachable one reports
 * nothing at all.
 */
export interface ServiceVersion {
  name: string
  commit: string
  shortCommit: string
  commitSubject: string | null
  commitTime: string | null
  startedAt: string | null
  reachable: boolean
}

/** One third-party image the compose file declares. */
export interface PlatformComponent {
  name: string
  image: string
  tag: string
  /** True when the tag does not pin a version, so the running digest is unknown. */
  floating: boolean
}

export interface PlatformStatus {
  services: ServiceVersion[]
  components: PlatformComponent[]
}

/** One changelog entry. `summary` is null until the sweep has written it. */
export interface Release {
  sha: string
  shortSha: string
  type: string
  subject: string
  commitTime: string
  running: boolean
  summary: string | null
  summaryStatus: ReleaseSummaryStatus
}
