import { Link } from 'react-router-dom'

import { FRONTEND_BUILD_TIME, FRONTEND_SHORT_COMMIT } from '../../config/version'

/**
 * The running frontend's commit, linking to /status.
 *
 * Rendered from the bundle's own baked SHA rather than from the API on purpose: this sits in
 * the footer of every page, so a fetch here would put a request on every single page view to
 * report a value the bundle already knows.
 *
 * It also makes /status discoverable without spending a seventh TopNav slot, which is the
 * change that would have hurt the mobile nav.
 */
export function VersionBadge() {
  const buildTime = FRONTEND_BUILD_TIME ? new Date(FRONTEND_BUILD_TIME) : null
  const validBuildTime = buildTime && !Number.isNaN(buildTime.getTime()) ? buildTime : null
  const title = validBuildTime
    ? `Version ${FRONTEND_SHORT_COMMIT}, built ${validBuildTime.toLocaleString()}`
    : `Version ${FRONTEND_SHORT_COMMIT}`

  return (
    <Link aria-label={title} className="version-badge" title={title} to="/status">
      <span className="version-badge__label">v</span>
      <span className="version-badge__sha">{FRONTEND_SHORT_COMMIT}</span>
    </Link>
  )
}
