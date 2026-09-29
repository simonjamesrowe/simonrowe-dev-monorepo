/**
 * PROTOTYPE — swaps the site chrome per variant. PublicLayout renders this instead of
 * TopNav + MobileMenu while a prototype variant is active.
 */
import { useLocation } from 'react-router-dom'

import { usePageTitle } from '../../hooks/usePageTitle'
import { HeaderA } from './HeaderA'
import { HeaderB } from './HeaderB'
import { HeaderC } from './HeaderC'
import { PortfolioSection } from './PortfolioSections'
import { usePrototypeVariant, type VariantKey } from './variant'
import './prototype.css'

export function PrototypeHeader({ variant }: { variant: VariantKey }) {
  if (variant === 'A') return <HeaderA />
  if (variant === 'B') return <HeaderB />
  if (variant === 'C') return <HeaderC />
  return null
}

export function usePrototypeLayoutClass(): { variant: VariantKey; className: string } {
  const variant = usePrototypeVariant()
  const { pathname } = useLocation()
  if (variant === 'current') return { variant, className: '' }
  return { variant, className: ` proto-active proto-${variant}${pathname === '/' ? ' proto-home' : ''}` }
}

export function PrototypePortfolioSection() {
  const variant = usePrototypeVariant()
  return <PortfolioSection variant={variant} />
}

export function PrototypePortfolioPage() {
  const variant = usePrototypeVariant()
  usePageTitle('Portfolio')
  return (
    <div className="pp-page">
      <PortfolioSection variant={variant === 'current' ? 'A' : variant} />
    </div>
  )
}
