import type { NarrationTrack } from '../../types/narrationAudio'
import type { PlaylistEntry } from '../../types/playlist'

/** `m:ss`, the clock every player on the site shows. */
export function formatClock(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  const whole = Math.floor(seconds)
  const minutes = Math.floor(whole / 60)
  return `${minutes}:${String(whole % 60).padStart(2, '0')}`
}

/** A rounded number of minutes for totals and estimates: "27 min", never "0 min". */
export function formatMinutes(seconds: number): string {
  return `${Math.max(1, Math.round(seconds / 60))} min`
}

/** What the player needs to play a playlist entry. Only entries with audio can be played. */
export function toTrack(entry: PlaylistEntry): NarrationTrack {
  return {
    contentType: entry.contentType,
    contentId: entry.contentId,
    title: entry.title,
    href: entry.href,
    external: entry.external,
    audioUrl: entry.audio.audioUrl,
    durationSeconds: entry.audio.durationSeconds,
    timingsUrl: entry.audio.timingsUrl,
    imageUrl: entry.imageUrl,
    sourceName: entry.sourceName,
    resumeAt: entry.status === 'QUEUED' ? entry.progressSeconds : 0,
    fromPlaylist: true,
  }
}

/** The label under a title: "Summary" for an article, "Blog post" for one of the site's own. */
export function kindOf(entry: PlaylistEntry): string {
  return entry.contentType === 'BLOG' ? 'Blog post' : 'Summary'
}

/**
 * The content's own date, as the news cards show it: "12 Oct", with the year only when it is
 * not this one.
 */
export function formatDate(iso: string | undefined, now: Date = new Date()): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    ...(date.getFullYear() === now.getFullYear() ? {} : { year: 'numeric' }),
  })
}

/** "2 h ago", "yesterday", "3 Oct": how long ago something joined or was heard. */
export function formatAgo(iso: string | undefined, now: number = Date.now()): string {
  if (!iso) return ''
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return ''
  const minutes = Math.round((now - then) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} h ago`
  if (hours < 48) return 'yesterday'
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })
}
