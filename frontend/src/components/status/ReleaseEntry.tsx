import type { Release } from '../../types/platform'

const COMMIT_URL = 'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/commit/'

interface ReleaseEntryProps {
  release: Release
}

/** One changelog entry. Renders from the commit subject when no summary exists yet. */
export function ReleaseEntry({ release }: ReleaseEntryProps) {
  const date = new Date(release.commitTime)
  const formatted = Number.isNaN(date.getTime()) ? null : date.toLocaleDateString()

  return (
    <li className={`release${release.running ? ' release--running' : ''}`}>
      <header className="release__header">
        <span className={`release__type release__type--${release.type}`}>{release.type}</span>
        <a
          className="release__sha"
          href={`${COMMIT_URL}${release.sha}`}
          rel="noopener noreferrer"
          target="_blank"
        >
          {release.shortSha}
        </a>
        {formatted ? <time className="release__date">{formatted}</time> : null}
        {release.running ? <span className="release__badge">Running now</span> : null}
      </header>

      <h3 className="release__subject">{release.subject}</h3>

      {release.summaryStatus === 'READY' && release.summary ? (
        <p className="release__summary">{release.summary}</p>
      ) : release.summaryStatus === 'FAILED' ? null : (
        <p className="release__summary release__summary--pending">Summary pending.</p>
      )}
    </li>
  )
}
