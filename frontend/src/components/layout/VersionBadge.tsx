import { Activity } from 'lucide-react'
import { Link } from 'react-router-dom'

import { FRONTEND_SHORT_COMMIT } from '../../config/version'

/**
 * Footer entry point to /status: one more icon in the existing footer icon row, styled
 * identically to its neighbours (GitHub, LinkedIn, Mail, Download) via `.footer__icon-link`.
 *
 * Rendered from the bundle's own baked SHA rather than from the API on purpose: this sits in
 * the footer of every page, so a fetch here would put a request on every single page view to
 * report a value the bundle already knows. Deliberately network-free: no hook, no API call.
 */
export function VersionBadge() {
  const label =
    FRONTEND_SHORT_COMMIT === 'dev'
      ? 'Platform status — dev build'
      : `Platform status — version ${FRONTEND_SHORT_COMMIT}`

  return (
    <Link aria-label={label} className="footer__icon-link" title={label} to="/status">
      <Activity size={18} />
    </Link>
  )
}
