import { useEffect, useRef, useState } from 'react'

import { useMealsApi } from './data'

// Object URLs for photos already fetched, kept for the life of the page: names are content
// hashes, so a URL never goes stale, and a photo seen once never costs a second request.
const cache = new Map<string, Promise<string>>()

/**
 * A recipe photo as an object URL. Photos need the bearer token, which an <img src> cannot
 * send, so they are fetched as blobs — and only once the placeholder scrolls near the screen,
 * because the library lists every recipe at once.
 */
export function usePhoto(name: string | null) {
  const api = useMealsApi()
  const ref = useRef<HTMLDivElement | null>(null)
  const [url, setUrl] = useState<string | null>(null)
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    const element = ref.current
    if (!name || !element) return
    if (typeof IntersectionObserver === 'undefined') {
      setVisible(true)
      return
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        setVisible(true)
        observer.disconnect()
      }
    }, { rootMargin: '300px' })
    observer.observe(element)
    return () => observer.disconnect()
  }, [name])

  useEffect(() => {
    if (!name || !visible) return
    let cancelled = false
    let pending = cache.get(name)
    if (!pending) {
      pending = api.image(name).then((blob) => URL.createObjectURL(blob))
      pending.catch(() => cache.delete(name))
      cache.set(name, pending)
    }
    pending.then((u) => { if (!cancelled) setUrl(u) }).catch(() => undefined)
    return () => { cancelled = true }
  }, [api, name, visible])

  return { ref, url }
}
