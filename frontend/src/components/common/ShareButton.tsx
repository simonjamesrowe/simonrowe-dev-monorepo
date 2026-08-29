import { Check, Share2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

/** How long the "Copied" confirmation stays up. */
const CONFIRMATION_MS = 2000

interface ShareButtonProps {
  /** The full absolute share URL. The backend sends it ready to use — never build one here. */
  url: string
  /** The item's title, used for the native sheet and the accessible name. */
  title: string
  /** True to hide the text label, for rows that are already crowded at mobile width. */
  iconOnly?: boolean
  className?: string
}

/**
 * The share control.
 *
 * Three tiers, checked at click time rather than at render:
 * 1. `navigator.share` — the OS share sheet, which is what a phone should do.
 * 2. `navigator.clipboard.writeText` — copy, and say so for two seconds.
 * 3. `document.execCommand('copy')` — only reachable in a non-secure context, realistically
 *    just local development over plain HTTP, where `navigator.clipboard` is undefined.
 *
 * Checked at click time deliberately: jsdom has neither of the first two, so detecting at
 * render would make every test exercise tier 3 and leave the paths that actually ship
 * untested.
 *
 * Cancelling the native sheet rejects with an `AbortError`. That is a person changing their
 * mind, not a failure, so it is swallowed.
 */
export function ShareButton({ url, title, iconOnly, className }: ShareButtonProps) {
  const [copied, setCopied] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current)
  }, [])

  const confirmCopied = () => {
    setCopied(true)
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), CONFIRMATION_MS)
  }

  const share = async () => {
    if (typeof navigator !== 'undefined' && typeof navigator.share === 'function') {
      try {
        await navigator.share({ title, url })
        return
      } catch (err) {
        // Dismissing the sheet is not a failure. Anything else falls through to the
        // clipboard, so a share sheet that errors still leaves a usable control.
        if ((err as Error)?.name === 'AbortError') return
      }
    }

    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(url)
        confirmCopied()
        return
      } catch {
        // Fall through — a denied clipboard permission is recoverable via execCommand.
      }
    }

    if (copyViaTextarea(url)) confirmCopied()
  }

  const label = copied ? 'Copied' : 'Share'

  return (
    <button
      aria-label={copied ? `Link to ${title} copied` : `Share ${title}`}
      className={`share-button${copied ? ' share-button--copied' : ''}${
        className ? ` ${className}` : ''
      }`}
      onClick={(e) => {
        // Cards are anchor elements — stop the click from opening the link behind them.
        e.preventDefault()
        e.stopPropagation()
        void share()
      }}
      type="button"
    >
      {copied
        ? <Check aria-hidden="true" size={14} />
        : <Share2 aria-hidden="true" size={14} />}
      {!iconOnly && <span>{label}</span>}
    </button>
  )
}

/**
 * The pre-Clipboard-API copy path.
 *
 * Only reachable where `navigator.clipboard` is undefined, which in practice means a
 * non-secure context — local development over plain HTTP. Kept so the control is never
 * simply dead there.
 */
function copyViaTextarea(value: string): boolean {
  if (typeof document === 'undefined') return false
  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  try {
    return document.execCommand?.('copy') ?? false
  } catch {
    return false
  } finally {
    document.body.removeChild(textarea)
  }
}
