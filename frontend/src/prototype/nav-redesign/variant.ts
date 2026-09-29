/**
 * PROTOTYPE — nav redesign mockups. Throwaway: see README.md in this folder.
 *
 * The variant comes from `?variant=` and is remembered in sessionStorage so it survives
 * clicking around the site (the real nav links do not carry the query string).
 */
import { useLocation } from 'react-router-dom'

export const VARIANTS = [
  { key: 'A', name: 'Floating capsule + dropdowns' },
  { key: 'B', name: 'Mega menu + mobile tab bar' },
  { key: 'C', name: 'Ask-first command bar + index' },
  { key: 'current', name: "Today's site" },
] as const

export type VariantKey = (typeof VARIANTS)[number]['key']

const STORAGE_KEY = 'prototype-nav-variant'

/**
 * Never true in a production build, so a stray merge cannot ship any of this. Off under Vitest
 * too (MODE 'test' is also DEV), so the suite keeps testing the real header and hero.
 */
export const PROTOTYPE_ENABLED = import.meta.env.DEV && import.meta.env.MODE !== 'test'

function isVariant(value: string | null): value is VariantKey {
  return VARIANTS.some(variant => variant.key === value)
}

export function usePrototypeVariant(): VariantKey {
  const { search } = useLocation()
  if (!PROTOTYPE_ENABLED) {
    return 'current'
  }
  const fromUrl = new URLSearchParams(search).get('variant')
  if (isVariant(fromUrl)) {
    sessionStorage.setItem(STORAGE_KEY, fromUrl)
    return fromUrl
  }
  const stored = sessionStorage.getItem(STORAGE_KEY)
  return isVariant(stored) ? stored : 'A'
}
