import { useEffect, useRef } from 'react'

import { NowPlayingPanel } from './NowPlayingPanel'

interface NowPlayingSheetProps {
  onClose: () => void
}

const FOCUSABLE = 'button:not([disabled]), a[href], input, [tabindex]:not([tabindex="-1"])'

/**
 * The full-screen player on a phone, opened from the docked bar on any page. Mounted only while
 * open. Focus moves to the title on open and stays inside until it closes; Escape closes it; the
 * page behind does not scroll.
 */
export function NowPlayingSheet({ onClose }: NowPlayingSheetProps) {
  const sheetRef = useRef<HTMLDivElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    headingRef.current?.focus()
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
        return
      }
      if (event.key !== 'Tab' || !sheetRef.current) return
      const focusable = Array.from(sheetRef.current.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (focusable.length === 0) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
    }
  }, [onClose])

  return (
    <div aria-label="Now playing" aria-modal="true" className="now-playing-sheet" ref={sheetRef} role="dialog">
      <NowPlayingPanel headingRef={headingRef} onClose={onClose} variant="sheet" />
    </div>
  )
}
