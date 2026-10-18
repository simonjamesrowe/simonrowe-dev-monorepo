import { useEffect, type ReactNode } from 'react'
import { Heart, Leaf, Moon, Snowflake, Sun, X } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useTheme } from '../contexts/ThemeContext'
import { useFavourites, useToggleFavourite } from './data'
import { usePhoto } from './photos'
import type { Coverage, Recipe } from './types'

export function Photo({ recipe, className = '' }: { recipe: Recipe; className?: string }) {
  const { ref, url } = usePhoto(recipe.photo)
  return (
    // A span, so a photo can sit inside a button.
    <span ref={ref} className={`meals-photo ${className}`}>
      {url ? <img src={url} alt="" /> : <span className="meals-photo__initial" aria-hidden="true">{recipe.name.slice(0, 1)}</span>}
    </span>
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

/**
 * A recipe tile. The heart sits beside the link rather than inside it: a button inside an <a> is
 * invalid, and a tap on it would also open the recipe.
 */
export function RecipeCard({ recipe, children }: { recipe: Recipe; children?: ReactNode }) {
  return (
    <div className="meals-card">
      <Link className="meals-card__link" to={`/recipes/${recipe.id}`}>
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
      <FavouriteButton recipe={recipe} className="meals-card__heart" />
    </div>
  )
}

/** The signed-in person's heart for one recipe. Kate's hearts and yours are separate. */
export function FavouriteButton({ recipe, className = '', withLabel = false }: {
  recipe: Recipe; className?: string; withLabel?: boolean
}) {
  const { ids } = useFavourites()
  const toggle = useToggleFavourite()
  const on = ids.has(recipe.id)
  return (
    <button type="button" className={`meals-heart ${on ? 'meals-heart--on' : ''} ${withLabel ? 'meals-button' : ''} ${className}`}
      aria-pressed={on} aria-label={withLabel ? undefined : `Favourite ${recipe.name}`}
      onClick={() => toggle.mutate({ recipeId: recipe.id, on: !on })}>
      <Heart size={18} fill={on ? 'currentColor' : 'none'} aria-hidden="true" />
      {withLabel && 'Favourite'}
    </button>
  )
}

export function Brand() {
  return (
    <span className="meals-brand">
      <img className="meals-brand__mark" src="/meals-logo.svg" alt="" width={34} height={34} />
      <span className="meals-brand__name">Meals</span>
    </span>
  )
}

/** Keeps the browser chrome (address bar, installed app's title bar) the colour of the page. */
export function ThemeColor() {
  const { theme } = useTheme()
  useEffect(() => {
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'light' ? '#faf6f1' : '#15120f')
  }, [theme])
  return null
}

export function ThemeToggle({ withLabel = false }: { withLabel?: boolean }) {
  const { theme, toggleTheme } = useTheme()
  const next = theme === 'dark' ? 'light' : 'dark'
  const Icon = theme === 'dark' ? Sun : Moon
  return (
    <button type="button" className={withLabel ? 'meals-button' : 'meals-icon-button'} onClick={toggleTheme}
      aria-label={withLabel ? undefined : `Switch to ${next} mode`}>
      <Icon size={18} aria-hidden="true" />
      {withLabel && `${next === 'light' ? 'Light' : 'Dark'} mode`}
    </button>
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

/** How much of a recipe the kitchen covers, as a bar and a short label. */
export function CoverageBar({ coverage }: { coverage: Coverage | undefined }) {
  if (!coverage) return null
  const toGet = coverage.missing.length + coverage.shortOf.length
  const label = toGet ? `${toGet} to get` : coverage.unknown.length ? `check ${coverage.unknown.length}` : 'Have it all'
  const tone = toGet ? '' : coverage.unknown.length ? 'meals-cover__label--check' : 'meals-cover__label--ok'
  return (
    <span className="meals-cover" title={`${Math.round(coverage.ratio * 100)}% of the ingredients are in`}>
      <span className="meals-cover__track"><span className="meals-cover__fill" style={{ width: `${Math.round(coverage.ratio * 100)}%` }} /></span>
      <span className={`meals-cover__label ${tone}`}>{label}</span>
    </span>
  )
}
