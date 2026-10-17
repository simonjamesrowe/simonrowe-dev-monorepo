import { useState } from 'react'
import { CalendarRange, ListTodo, Minus, Plus, ShoppingBasket, Trash2 } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useCoverage, useMine, useUpNext } from './data'
import { CoverageBar, Empty, Loading, Photo, Sheet } from './ui'
import type { Recipe, Template } from './types'

/** What the owner means to cook soon. No days, no slots: the shopping list is built from this. */
export function UpNext({ byId, templates }: { byId: Map<string, Recipe>; templates: Template[] }) {
  const upNext = useUpNext()
  const coverage = useCoverage().data
  const [weeks, setWeeks] = useState(false)
  const setPortions = useMine((api, v: { id: string; portions: number }) => api.queue(v.id, v.portions))
  const remove = useMine((api, id: string) => api.unqueue(id))
  const addWeek = useMine((api, id: string) => api.queueTemplate(id))
  const items = (upNext.data ?? []).filter((u) => byId.has(u.recipeId))

  return (
    <div className="meals-page">
      <header className="meals-page__header">
        <h1>Up next</h1>
        <p className="meals-muted">What you mean to cook soon. The shopping list is built from this.</p>
      </header>
      <div className="meals-row">
        <button type="button" className="meals-button" onClick={() => setWeeks(true)}><CalendarRange size={16} /> Start from one of Dave's weeks</button>
        {items.length > 0 && <Link className="meals-button meals-button--primary" to="/shop"><ShoppingBasket size={16} /> Shopping list</Link>}
      </div>

      {upNext.isLoading ? <Loading /> : items.length === 0 ? (
        <Empty icon={<ListTodo size={32} />} title="Nothing queued">
          Add recipes from a recipe page or the Cook screen, or start from one of Dave's weeks.
        </Empty>
      ) : (
        <ul className="meals-queue">
          {items.map((u) => {
            const recipe = byId.get(u.recipeId)!
            return (
              <li key={u.recipeId} className="meals-queue__item">
                <Link className="meals-queue__open" to={`/recipes/${recipe.id}`}>
                  <Photo recipe={recipe} className="meals-queue__photo" />
                  <span className="meals-queue__body">
                    <span className="meals-queue__name">{recipe.name}</span>
                    <CoverageBar coverage={coverage?.[recipe.id]} />
                  </span>
                </Link>
                <span className="meals-stepper meals-stepper--small" role="group" aria-label={`${recipe.name} portions`}>
                  <button type="button" className="meals-icon-button" aria-label="Fewer portions" disabled={u.portions <= 1}
                    onClick={() => setPortions.mutate({ id: u.recipeId, portions: u.portions - 1 })}><Minus size={14} /></button>
                  <span>{u.portions}</span>
                  <button type="button" className="meals-icon-button" aria-label="More portions"
                    onClick={() => setPortions.mutate({ id: u.recipeId, portions: u.portions + 1 })}><Plus size={14} /></button>
                </span>
                <button type="button" className="meals-icon-button" aria-label={`Remove ${recipe.name}`} onClick={() => remove.mutate(u.recipeId)}><Trash2 size={16} /></button>
              </li>
            )
          })}
        </ul>
      )}

      {weeks && (
        <WeekPicker templates={templates} byId={byId} onClose={() => setWeeks(false)}
          onPick={(t) => addWeek.mutate(t.id, { onSuccess: () => setWeeks(false) })} />
      )}
    </div>
  )
}

function WeekPicker({ templates, byId, onClose, onPick }: {
  templates: Template[]; byId: Map<string, Recipe>; onClose: () => void; onPick: (t: Template) => void
}) {
  const sources = [...new Set(templates.map((t) => t.source))]
  const [source, setSource] = useState(sources[0] ?? '')
  return (
    <Sheet title="Dave's weeks" onClose={onClose}>
      <p className="meals-muted meals-small">Each week is a set of recipes Dave put together. Adding one queues its recipes; the calorie figure is just his label.</p>
      {sources.length > 1 && (
        <div className="meals-segmented" role="tablist" aria-label="Book">
          {sources.map((s) => (
            <button key={s} type="button" role="tab" aria-selected={s === source}
              className={s === source ? 'meals-segmented__on' : ''} onClick={() => setSource(s)}>{s}</button>
          ))}
        </div>
      )}
      <ul className="meals-weeks">
        {templates.filter((t) => t.source === source).map((t) => {
          const recipes = t.recipeIds.map((id) => byId.get(id)).filter(Boolean) as Recipe[]
          return (
            <li key={t.id} className="meals-week">
              <div className="meals-week__head">
                <strong>{t.label}</strong>
                <button type="button" className="meals-button meals-button--small" onClick={() => onPick(t)}><Plus size={14} /> Add {recipes.length}</button>
              </div>
              <div className="meals-week__thumbs">
                {recipes.map((r) => <span key={r.id} className="meals-week__thumb" title={r.name}><Photo recipe={r} /><span>{r.name}</span></span>)}
              </div>
            </li>
          )
        })}
      </ul>
    </Sheet>
  )
}
