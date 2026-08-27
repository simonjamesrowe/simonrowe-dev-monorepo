import type { Release } from '../../types/platform'

import { ReleaseEntry } from './ReleaseEntry'

interface ReleaseListProps {
  releases: Release[]
}

export function ReleaseList({ releases }: ReleaseListProps) {
  if (releases.length === 0) {
    return <p className="status-page__empty">No release history yet.</p>
  }

  return (
    <ol className="release-list">
      {releases.map((release) => (
        <ReleaseEntry key={release.sha} release={release} />
      ))}
    </ol>
  )
}
