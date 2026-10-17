import { useEffect, useState } from 'react'
import { ArrowLeft, Check, ChefHat, ChevronLeft, ChevronRight, Minus, Plus, Trash2, X } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

import { CookedSheet } from './CookedSheet'
import { useDeleteRating } from './data'
import { averageScore, BOOKS, ratingsFor, scaledLine } from './logic'
import { Flags, Macros, Photo, Stars } from './ui'
import type { Ingredient, Rating, Recipe } from './types'

interface Props {
  recipe: Recipe
  ratings: Rating[]
  ingredients: Map<string, Ingredient>
}

export function RecipePage({ recipe, ratings, ingredients }: Props) {
  const navigate = useNavigate()
  const [portions, setPortions] = useState(recipe.servings)
  const [cooking, setCooking] = useState(false)
  const [cooked, setCooked] = useState(false)
  const deleteRating = useDeleteRating()
  const scale = portions / recipe.servings
  const history = ratingsFor(recipe.id, ratings)
  const avg = averageScore(recipe.id, ratings)
  const book = recipe.source.book ? BOOKS[recipe.source.book] ?? recipe.source.book : null
  const page = recipe.source.pages[0]

  useEffect(() => { window.scrollTo(0, 0) }, [recipe.id])

  return (
    <article className="meals-recipe">
      <div className="meals-recipe__hero">
        <Photo recipe={recipe} className="meals-recipe__photo" />
        <button type="button" className="meals-recipe__back meals-icon-button" aria-label="Back"
          onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/recipes'))}>
          <ArrowLeft size={20} />
        </button>
      </div>
      <div className="meals-recipe__content">
        <p className="meals-eyebrow">{[recipe.section, book && `${book}${page ? `, p.${page}` : ''}`].filter(Boolean).join(' · ')}</p>
        <h1 className="meals-recipe__title">{recipe.name}</h1>
        <div className="meals-recipe__sub">
          <Flags recipe={recipe} />
          {avg != null && <span className="meals-rating-pill">★ {avg.toFixed(1)} · cooked {history.length}×</span>}
        </div>
        <Macros recipe={recipe} />
        <p className="meals-muted meals-small">
          Per serving{recipe.macrosEstimated ? ', estimated' : ''}. The recipe makes {recipe.servings}.
        </p>

        <div className="meals-row">
          <button type="button" className="meals-button" onClick={() => setCooking(true)}><ChefHat size={16} /> Cook mode</button>
          <button type="button" className="meals-button meals-button--good" onClick={() => setCooked(true)}><Check size={16} /> Cooked it</button>
        </div>

        <section className="meals-recipe__section">
          <div className="meals-recipe__section-head">
            <h2>Ingredients</h2>
            <div className="meals-stepper" role="group" aria-label="Portions">
              <button type="button" className="meals-icon-button" onClick={() => setPortions(Math.max(1, portions - 1))} aria-label="Fewer portions"><Minus size={16} /></button>
              <span><strong>{portions}</strong> {portions === 1 ? 'portion' : 'portions'}</span>
              <button type="button" className="meals-icon-button" onClick={() => setPortions(portions + 1)} aria-label="More portions"><Plus size={16} /></button>
            </div>
          </div>
          <ul className="meals-ingredients">
            {recipe.ingredients.map((line, i) => (
              <li key={i} className={`meals-ingredient ${ingredients.get(line.name)?.assumed ? 'meals-ingredient--assumed' : ''}`}>
                {scaledLine(line, scale)}
              </li>
            ))}
          </ul>
        </section>

        <section className="meals-recipe__section">
          <h2>Method</h2>
          <ol className="meals-method">{recipe.method.map((step, i) => <li key={i}>{step}</li>)}</ol>
        </section>

        {(recipe.tips.length > 0 || recipe.swaps.length > 0) && (
          <section className="meals-recipe__section meals-recipe__notes">
            {recipe.tips.length > 0 && <div><h3>Tips</h3><ul>{recipe.tips.map((t, i) => <li key={i}>{t}</li>)}</ul></div>}
            {recipe.swaps.length > 0 && <div><h3>Swaps</h3><ul>{recipe.swaps.map((t, i) => <li key={i}>{t}</li>)}</ul></div>}
          </section>
        )}

        {history.length > 0 && (
          <section className="meals-recipe__section">
            <h2>Your notes</h2>
            <ul className="meals-history">
              {history.map((h) => (
                <li key={h.id}>
                  <Stars value={h.score} size={16} />
                  <span className="meals-muted meals-small">
                    {new Date(h.at).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })}{h.again ? '' : ' · not again'}
                  </span>
                  {h.tags.map((t) => <span key={t} className="meals-tag">{t}</span>)}
                  <button type="button" className="meals-icon-button meals-icon-button--small" aria-label="Delete this rating"
                    onClick={() => deleteRating.mutate(h.id)}><Trash2 size={14} /></button>
                  {h.notes && <p>{h.notes}</p>}
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>

      {cooking && <CookMode recipe={recipe} scale={scale} onClose={() => setCooking(false)}
        onDone={() => { setCooking(false); setCooked(true) }} />}
      {cooked && <CookedSheet recipe={recipe} onClose={() => setCooked(false)} onSaved={() => setCooked(false)} />}
    </article>
  )
}

function CookMode({ recipe, scale, onClose, onDone }: { recipe: Recipe; scale: number; onClose: () => void; onDone: () => void }) {
  const [step, setStep] = useState(-1)
  const last = recipe.method.length - 1

  // Keep the screen on while cooking, and release it when cook mode closes.
  useEffect(() => {
    let lock: WakeLockSentinel | null = null
    let released = false
    navigator.wakeLock?.request('screen')
      .then((l) => { if (released) l.release().catch(() => undefined); else lock = l })
      .catch(() => undefined)
    return () => {
      released = true
      lock?.release().catch(() => undefined)
    }
  }, [])

  return (
    <div className="meals-cook" role="dialog" aria-modal="true" aria-label={`Cooking ${recipe.name}`}>
      <header className="meals-cook__header">
        <span className="meals-cook__name">{recipe.name}</span>
        <button type="button" className="meals-icon-button" onClick={onClose} aria-label="Close cook mode"><X size={22} /></button>
      </header>
      <div className="meals-cook__body">
        {step === -1 ? (
          <>
            <p className="meals-eyebrow">Get out</p>
            <ul className="meals-cook__ingredients">{recipe.ingredients.map((l, i) => <li key={i}>{scaledLine(l, scale)}</li>)}</ul>
          </>
        ) : (
          <>
            <p className="meals-eyebrow">Step {step + 1} of {recipe.method.length}</p>
            <p className="meals-cook__step">{recipe.method[step]}</p>
          </>
        )}
      </div>
      <footer className="meals-cook__footer">
        <button type="button" className="meals-button" disabled={step === -1} onClick={() => setStep(step - 1)}><ChevronLeft size={18} /> Back</button>
        {step < last
          ? <button type="button" className="meals-button meals-button--primary" onClick={() => setStep(step + 1)}>{step === -1 ? 'Start' : 'Next'} <ChevronRight size={18} /></button>
          : <button type="button" className="meals-button meals-button--good" onClick={onDone}><Check size={18} /> Done</button>}
      </footer>
    </div>
  )
}
