import { useEffect } from 'react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { CollapsibleSection } from '../components/status/CollapsibleSection'
import { ComponentTable } from '../components/status/ComponentTable'
import { DriftWarning } from '../components/status/DriftWarning'
import { ReleaseList } from '../components/status/ReleaseList'
import { ServiceVersionCard } from '../components/status/ServiceVersionCard'
import { frontendServiceVersion } from '../config/version'
import { usePageTitle } from '../hooks/usePageTitle'
import { usePlatformStatus } from '../hooks/usePlatformStatus'
import { useReleases } from '../hooks/useReleases'
import { trackPageView } from '../services/analytics'

/**
 * What is running in production, and what shipped recently.
 *
 * The frontend's own entry comes from the bundle rather than from the API — the backend
 * cannot know which bundle a browser loaded.
 */
export function StatusPage() {
  const { status, loading, error, retry } = usePlatformStatus()
  const {
    releases,
    loading: releasesLoading,
    error: releasesError,
    retry: retryReleases,
  } = useReleases()

  usePageTitle('Platform Status')

  useEffect(() => {
    trackPageView('/status')
  }, [])

  // The backend reports itself first; the frontend inserts itself second so the two
  // versions that most often drift sit next to each other.
  const services = status
    ? [status.services[0], frontendServiceVersion(), ...status.services.slice(1)].filter(Boolean)
    : [frontendServiceVersion()]

  return (
    <div className="status-page">
      <header className="status-page__header">
        <h1 className="status-page__title">Platform Status</h1>
        <p className="status-page__intro">
          What is running in production right now, and what shipped recently. Versions are the
          commit each service was built from — there are no release tags, the SHA is the version.
        </p>
      </header>

      <section className="status-page__section">
        <h2 className="status-page__section-title">Running now</h2>
        {loading ? <LoadingIndicator /> : null}
        {error ? <ErrorMessage message={error} onRetry={retry} /> : null}
        <DriftWarning services={services} />
        <div className="status-page__services">
          {services.map((service) => (
            <ServiceVersionCard key={service.name} version={service} />
          ))}
        </div>
      </section>

      <section className="status-page__section">
        <CollapsibleSection
          count={loading ? undefined : (status?.components.length ?? 0)}
          defaultOpen={false}
          title="Platform components"
        >
          <p className="status-page__note">
            The third-party images the production compose file declares. Pinned tags are what is
            running; a floating tag means the running digest is not pinned and cannot be
            reported.
          </p>
          <ComponentTable components={status?.components ?? []} />
        </CollapsibleSection>
      </section>

      <section className="status-page__section">
        <CollapsibleSection
          count={releasesLoading ? undefined : releases.length}
          defaultOpen
          title="Recent releases"
        >
          <p className="status-page__note">
            Every merge to <code>main</code> publishes an image, so one commit is one release.
            Entries other than the one running now record what was <strong>published</strong>,
            not what was deployed — deploys are manual, so there is no deployment history to
            report. Summaries are written by a model when a release is first seen.
          </p>
          {releasesLoading ? <LoadingIndicator /> : null}
          {releasesError ? (
            <ErrorMessage message={releasesError} onRetry={retryReleases} />
          ) : null}
          <ReleaseList releases={releases} />
        </CollapsibleSection>
      </section>
    </div>
  )
}
