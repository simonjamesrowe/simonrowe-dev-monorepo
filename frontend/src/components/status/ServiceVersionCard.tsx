import type { ServiceVersion } from '../../types/platform'

const COMMIT_URL = 'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/commit/'
const UNKNOWN_COMMIT = 'unknown'

interface ServiceVersionCardProps {
  version: ServiceVersion
}

function formatDate(value: string | null): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toLocaleString()
}

function formatUptime(startedAt: string | null): string | null {
  if (!startedAt) return null
  const started = new Date(startedAt)
  if (Number.isNaN(started.getTime())) return null
  const hours = Math.floor((Date.now() - started.getTime()) / 3_600_000)
  if (hours < 1) return 'less than an hour'
  if (hours < 48) return `${hours} hour${hours === 1 ? '' : 's'}`
  return `${Math.floor(hours / 24)} days`
}

/** One first-party service's version facts. Absent facts are omitted, never faked. */
export function ServiceVersionCard({ version }: ServiceVersionCardProps) {
  const commitTime = formatDate(version.commitTime)
  const startedAt = formatDate(version.startedAt)
  const uptime = formatUptime(version.startedAt)

  return (
    <article className="service-card">
      <header className="service-card__header">
        <h3 className="service-card__name">{version.name}</h3>
        {version.reachable ? (
          version.commit === UNKNOWN_COMMIT ? (
            <span className="service-card__sha">{version.shortCommit}</span>
          ) : (
            <a
              className="service-card__sha"
              href={`${COMMIT_URL}${version.commit}`}
              rel="noopener noreferrer"
              target="_blank"
            >
              {version.shortCommit}
            </a>
          )
        ) : (
          <span className="service-card__sha service-card__sha--unknown">not reporting</span>
        )}
      </header>

      {version.commitSubject ? (
        <p className="service-card__subject">{version.commitSubject}</p>
      ) : null}

      <dl className="service-card__facts">
        {commitTime ? (
          <>
            <dt>Committed</dt>
            <dd>{commitTime}</dd>
          </>
        ) : null}
        {startedAt ? (
          <>
            <dt>Started</dt>
            <dd>
              {startedAt}
              {uptime ? ` (${uptime} ago)` : ''}
            </dd>
          </>
        ) : null}
      </dl>
    </article>
  )
}
