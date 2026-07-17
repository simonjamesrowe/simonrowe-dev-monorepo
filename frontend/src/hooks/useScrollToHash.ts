import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'

/**
 * Scrolls to the element whose id matches the current location hash. Pass `ready=false`
 * while a page is still loading its content so the scroll runs once the target section exists.
 */
export function useScrollToHash(ready: boolean = true): void {
  const { hash } = useLocation()

  useEffect(() => {
    if (!ready || !hash) {
      return
    }
    const id = decodeURIComponent(hash.slice(1))
    const element = document.getElementById(id)
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  }, [hash, ready])
}
