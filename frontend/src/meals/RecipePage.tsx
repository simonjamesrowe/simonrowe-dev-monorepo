import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  ArrowLeft, Check, ChefHat, ChevronLeft, ChevronRight, ExternalLink, ListPlus, ListX, Minus, Pencil, Plus, Trash2, X,
} from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'

import { CookedSheet } from './CookedSheet'
import { MINE, useDeleteRating, useKitchenHasFood, useMealsApi, useMine, useUpNext } from './data'
import { averageScore, ratingsFor, scaledLine, sourceOf } from './logic'
import { FavouriteButton, Flags, Macros, Photo, Stars } from './ui'
import type { Ingredient, Rating, Recipe } from './types'

interface Props {
  recipe: Recipe
  ratings: Rating[]
  ingredients: Map<string, Ingredient>
}

export function RecipePage({ recipe, ratings, ingredients }: Props) {
  const navigate = useNavigate()
  const api = useMealsApi()
  const upNext = useUpNext()
  const queued = upNext.data?.find((u) => u.recipeId === recipe.id)
  const [portions, setPortions] = useState(queued?.portions ?? recipe.servings)
  const { hasFood } = useKitchenHasFood()
  // Against an empty kitchen every fresh line reads as missing, which says nothing useful.
  const coverage = useQuery({
    queryKey: [...MINE, 'recipe-coverage', recipe.id, portions],
    queryFn: () => api.recipeCoverage(recipe.id, portions),
    enabled: hasFood,
  }).data
  const queue = useMine((a, p: number) => a.queue(recipe.id, p))
  const unqueue = useMine((a) => a.unqueue(recipe.id))
  const [cooking, setCooking] = useState(false)
  const [cooked, setCooked] = useState(false)
  const deleteRating = useDeleteRating()
  const scale = portions / recipe.servings
  const history = ratingsFor(recipe.id, ratings)
  const avg = averageScore(recipe.id, ratings)
  const source = sourceOf(recipe)

  useEffect(() => { window.scrollTo(0, 0) }, [recipe.id])
  useEffect(() => { if (queued) setPortions(queued.portions) }, [queued])

  function changePortions(next: number) {
    const value = Math.max(1, Math.min(50, next))
    setPortions(value)
    if (queued) queue.mutate(value)
  }

  const toGet = coverage ? [...coverage.missing, ...coverage.shortOf] : []

  return (
    <article className="meals-recipe">
      <div className="meals-recipe__hero">
        <Photo recipe={recipe} className="meals-recipe__photo" />
        <button type="button" className="meals-recipe__back meals-icon-button" aria-label="Back"
          onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/recipes'))}>
          <ArrowLeft size={20} />
        </button>
        <FavouriteButton recipe={recipe} className="meals-recipe__heart" />
      </div>
      <div className="meals-recipe__content">
        {recipe.section && <p className="meals-eyebrow">{recipe.section}</p>}
        <h1 className="meals-recipe__title">{recipe.name}</h1>
        {source && (
          <p className="meals-source">
            Source:{' '}
            {source.url
              ? <a href={source.url} target="_blank" rel="noopener noreferrer">{source.label} <ExternalLink size={12} aria-hidden="true" /></a>
              : source.label}
          </p>
        )}
        <div className="meals-recipe__sub">
          <Flags recipe={recipe} />
          {avg != null && <span className="meals-rating-pill">★ {avg.toFixed(1)} · cooked {history.length}×</span>}
        </div>
        <Macros recipe={recipe} />
        <p className="meals-muted meals-small">
          Per serving{recipe.macrosEstimated ? ', estimated' : ''}. The recipe makes {recipe.servings}.
        </p>

        <div className="meals-row">
          <button type="button" className={`meals-button ${queued ? '' : 'meals-button--primary'}`}
            onClick={() => (queued ? unqueue.mutate(undefined) : queue.mutate(portions))}>
            {queued ? <><ListX size={16} /> Remove from Up next</> : <><ListPlus size={16} /> Add to Up next</>}
          </button>
          <button type="button" className="meals-button" onClick={() => setCooking(true)}><ChefHat size={16} /> Cook mode</button>
          <button type="button" className="meals-button meals-button--good" onClick={() => setCooked(true)}><Check size={16} /> Cooked it</button>
          <Link className="meals-button" to={`/recipes/${recipe.id}/edit`}><Pencil size={16} /> Edit</Link>
        </div>

        <section className="meals-recipe__section">
          <div className="meals-recipe__section-head">
            <h2>Ingredients</h2>
            <div className="meals-stepper" role="group" aria-label="Portions">
              <button type="button" className="meals-icon-button" onClick={() => changePortions(portions - 1)} aria-label="Fewer portions"><Minus size={16} /></button>
              <span><strong>{portions}</strong> {portions === 1 ? 'portion' : 'portions'}</span>
              <button type="button" className="meals-icon-button" onClick={() => changePortions(portions + 1)} aria-label="More portions"><Plus size={16} /></button>
            </div>
          </div>
          {coverage && (
            <p className="meals-muted meals-small">
              {toGet.length === 0 ? 'You have everything for this.' : `To get: ${toGet.join(', ')}.`}
              {coverage.unknown.length > 0 && ` Check the cupboard for ${coverage.unknown.join(', ')}.`}
            </p>
          )}
          <ul className="meals-ingredients">
            {recipe.ingredients.map((line, i) => {
              const state = ingredients.get(line.name)?.assumed ? 'assumed' : coverage ? coverage.lines[line.name]?.toLowerCase() ?? 'unknown' : 'plain'
              return (
                <li key={i} className={`meals-ingredient meals-ingredient--${state}`}>
                  {scaledLine(line, scale)}
                  {state === 'short' && <span className="meals-tag">not enough</span>}
                  {state === 'unknown' && coverage && <span className="meals-tag">check</span>}
                </li>
              )
            })}
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
      {cooked && <CookedSheet recipe={recipe} portions={portions} onClose={() => setCooked(false)} onSaved={() => setCooked(false)} />}
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
