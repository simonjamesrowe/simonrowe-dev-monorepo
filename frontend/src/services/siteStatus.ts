/**
 * Asks the reverse proxy what state the site is in, from the browser.
 *
 * There is no API for this and there should not be: the answer has to come from the one
 * component that is still serving when everything behind it is not. `config/nginx/
 * nginx-proxy.conf` already encodes it in the status line — 503 while
 * /var/run/deploy-state/maintenance.on exists, 502/504 when an upstream is unreachable — so
 * this reads that rather than adding a second source of truth that a deploy would have to keep
 * in step with the first.
 */
export type SiteStatus =
  /** Serving normally. */
  | 'ok'
  /** A deploy is in progress: nginx is serving the maintenance page for this URL. */
  | 'maintenance'
  /** No deploy, but the upstream behind this URL is down. */
  | 'unavailable'
  /** The request itself did not complete — offline, DNS, a dead tunnel. */
  | 'unreachable'

/**
 * Probes the page the visitor is actually on, not a fixed path.
 *
 * Deliberately `window.location.href`: the maintenance flag is checked per server block, and
 * Term Time is a different hostname from the main site with its own block. Probing `/` of some
 * canonical host would answer a question about a site the visitor is not looking at.
 *
 * HEAD, and `cache: 'no-store'`. A cached 200 from before the deploy started is exactly the
 * wrong answer, and it is the answer a plain GET would be entitled to give.
 */
export async function probeSiteStatus(signal?: AbortSignal): Promise<SiteStatus> {
  try {
    const response = await fetch(window.location.href, {
      method: 'HEAD',
      cache: 'no-store',
      signal,
    })
    if (response.status === 503) return 'maintenance'
    if (response.status === 502 || response.status === 504) return 'unavailable'
    return 'ok'
  } catch {
    // Includes the abort. A caller that aborted is not interested in the answer, and every
    // caller treats 'unreachable' as "keep waiting" — the same thing it would do on abort.
    return 'unreachable'
  }
}

/**
 * Reloads the page the visitor is on.
 *
 * A one-line wrapper, and it earns its place: it sits beside the probe because the two are
 * one decision — "what state is this url in, and what do I do about it" — and because
 * `window.location.reload` is non-configurable in jsdom, so a caller that reaches for it
 * directly cannot be tested at all. This is the seam.
 */
export function reloadPage(): void {
  window.location.reload()
}
