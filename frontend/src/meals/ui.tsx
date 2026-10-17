import { useEffect, type ReactNode } from 'react'
import { Leaf, Snowflake, X } from 'lucide-react'
import { Link } from 'react-router-dom'

import { usePhoto } from './photos'
import type { Recipe } from './types'

export function Photo({ recipe, className = '' }: { recipe: Recipe; className?: string }) {
  const { ref, url } = usePhoto(recipe.photo)
  return (
    <div ref={ref} className={`meals-photo ${className}`}>
      {url ? <img src={url} alt="" /> : <span className="meals-photo__initial" aria-hidden="true">{recipe.name.slice(0, 1)}</span>}
    </div>
  )
}

export function Macros({ recipe }: { recipe: Recipe }) {
  const m = recipe.perServing
  return (
    <dl className="meals-macros">
      <div><dt>kcal</dt><dd>{Math.round(m.kcal)}</dd></div>
      <div><dt>protein</dt><dd>{Math.round(m.protein)}g</dd></div>
      <div><dt>carbs</dt><dd>{Math.round(m.carbs)}g</dd></div>
      <div><dt>fat</dt><dd>{Math.round(m.fat)}g</dd></div>
    </dl>
  )
}

export function Flags({ recipe }: { recipe: Recipe }) {
  return (
    <span className="meals-flags">
      {recipe.vegetarian && (
        <span className="meals-flag meals-flag--veg" title={recipe.vegan ? 'Vegan' : 'Vegetarian'}>
          <Leaf size={12} aria-hidden="true" /> {recipe.vegan ? 'VG' : 'V'}
        </span>
      )}
      {recipe.freezable && <span className="meals-flag" title="Freezes"><Snowflake size={12} aria-label="Freezes" /></span>}
    </span>
  )
}

export function RecipeCard({ recipe, children }: { recipe: Recipe; children?: ReactNode }) {
  return (
    <Link className="meals-card" to={`/recipes/${recipe.id}`}>
      <Photo recipe={recipe} className="meals-card__photo" />
      <span className="meals-card__body">
        <span className="meals-card__name">{recipe.name}</span>
        <span className="meals-card__meta">
          <span className="meals-card__macros">{Math.round(recipe.perServing.kcal)} kcal · {Math.round(recipe.perServing.protein)}g protein</span>
          <Flags recipe={recipe} />
        </span>
        {children}
      </span>
    </Link>
  )
}

export function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    document.body.classList.add('meals-no-scroll')
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.classList.remove('meals-no-scroll')
    }
  }, [onClose])
  return (
    <div className="meals-sheet" role="dialog" aria-modal="true" aria-label={title}>
      <button type="button" className="meals-sheet__backdrop" aria-label="Close" tabIndex={-1} onClick={onClose} />
      <div className="meals-sheet__panel">
        <header className="meals-sheet__header">
          <h2>{title}</h2>
          <button type="button" className="meals-icon-button" onClick={onClose} aria-label="Close"><X size={20} /></button>
        </header>
        <div className="meals-sheet__body">{children}</div>
      </div>
    </div>
  )
}

export function Stars({ value, onChange, size = 28 }: { value: number; onChange?: (v: number) => void; size?: number }) {
  return (
    <span className="meals-stars" role={onChange ? 'radiogroup' : 'img'} aria-label={onChange ? 'Rating' : `${value} out of 5`}>
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          key={n}
          type="button"
          className={`meals-stars__star ${n <= value ? 'meals-stars__star--on' : ''}`}
          style={{ fontSize: size }}
          disabled={!onChange}
          role={onChange ? 'radio' : undefined}
          aria-checked={onChange ? n === value : undefined}
          aria-label={`${n} star${n > 1 ? 's' : ''}`}
          onClick={() => onChange?.(n)}
        >★</button>
      ))}
    </span>
  )
}

export function Empty({ icon, title, children }: { icon: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="meals-empty">
      <div className="meals-empty__icon">{icon}</div>
      <h3>{title}</h3>
      {children && <div className="meals-empty__body">{children}</div>}
    </div>
  )
}

export function Loading() {
  return <div className="meals-loading" role="status">Loading…</div>
}
