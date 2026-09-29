/** PROTOTYPE — floating variant switcher. Dev builds only. */
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { PROTOTYPE_ENABLED, VARIANTS, usePrototypeVariant } from './variant'

export function PrototypeSwitcher() {
  const current = usePrototypeVariant()
  const navigate = useNavigate()
  const location = useLocation()
  const index = VARIANTS.findIndex(variant => variant.key === current)

  const go = (step: number) => {
    const next = VARIANTS[(index + step + VARIANTS.length) % VARIANTS.length]
    const params = new URLSearchParams(location.search)
    params.set('variant', next.key)
    navigate({ pathname: location.pathname, search: params.toString(), hash: location.hash }, { replace: true })
  }

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, [contenteditable="true"]')) return
      if (event.key === 'ArrowLeft') go(-1)
      if (event.key === 'ArrowRight') go(1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  if (!PROTOTYPE_ENABLED) return null

  return (
    <div className="proto-switcher" role="toolbar" aria-label="Prototype variant switcher">
      <button aria-label="Previous variant" onClick={() => go(-1)} type="button"><ChevronLeft size={16} /></button>
      <span className="proto-switcher__label">
        <strong>{VARIANTS[index].key}</strong> {VARIANTS[index].name}
      </span>
      <button aria-label="Next variant" onClick={() => go(1)} type="button"><ChevronRight size={16} /></button>
    </div>
  )
}
