import { isAnalyticsConfigured, openCookieSettings } from './analytics'

interface CookieSettingsLinkProps {
  className?: string
}

/**
 * Re-opens the consent choices, so a visitor can withdraw as easily as they agreed.
 * Renders nothing in a build without analytics, where there is no choice to revisit.
 */
export function CookieSettingsLink({ className = 'cookie-settings-link' }: CookieSettingsLinkProps) {
  if (!isAnalyticsConfigured()) return null
  return (
    <button type="button" className={className} onClick={openCookieSettings}>
      Cookie settings
    </button>
  )
}
