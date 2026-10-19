import { useEffect, useState } from 'react'

import { track } from '../analytics/analytics'
import { useMealsApi, useMine } from './data'
import { formatQty, TAGS } from './logic'
import { Sheet, Stars } from './ui'
import type { CookedRequest, Deduction, Recipe } from './types'

/**
 * The one log the app keeps: how a recipe went, and what cooking it took out of the kitchen.
 * The deductions start at what the recipe needs, capped at what is in, and can be adjusted.
 */
export function CookedSheet({ recipe, portions, onClose, onSaved }: {
  recipe: Recipe; portions: number; onClose: () => void; onSaved: () => void
}) {
  const api = useMealsApi()
  const [score, setScore] = useState(0)
  const [again, setAgain] = useState(true)
  const [tags, setTags] = useState<string[]>([])
  const [notes, setNotes] = useState('')
  const [deductions, setDeductions] = useState<Deduction[] | null>(null)
  const [stock, setStock] = useState<Set<string>>(new Set())
  const cooked = useMine((a, body: CookedRequest) => a.cooked(body))

  useEffect(() => {
    let cancelled = false
    Promise.all([api.deductions(recipe.id, portions), api.kitchen()])
      .then(([d, kitchen]) => {
        if (cancelled) return
        setDeductions(d)
        setStock(new Set(kitchen.map((k) => k.name)))
      })
      .catch(() => { if (!cancelled) setDeductions([]) })
    return () => { cancelled = true }
  }, [api, recipe.id, portions])

  const inKitchen = (deductions ?? []).filter((d) => stock.has(d.name))
  const setD = (name: string, patch: Partial<Deduction>) =>
    setDeductions((ds) => ds && ds.map((d) => (d.name === name ? { ...d, ...patch } : d)))

  function save() {
    cooked.mutate({ recipeId: recipe.id, portions, score, again, tags, notes: notes.trim(), deductions: inKitchen },
      {
        onSuccess: () => {
          track('Recipe Cooked')
          onSaved()
        },
      })
  }

  return (
    <Sheet title={`Cooked ${recipe.name}`} onClose={onClose}>
      <section className="meals-form-block">
        <h3>How was it?</h3>
        <Stars value={score} onChange={setScore} />
        <label className="meals-toggle">
          <input type="checkbox" checked={again} onChange={(e) => setAgain(e.target.checked)} />
          <span>Would make again</span>
        </label>
        <div className="meals-chips">
          {TAGS.map((t) => (
            <button key={t} type="button" aria-pressed={tags.includes(t)}
              className={`meals-chip ${tags.includes(t) ? 'meals-chip--on' : ''}`}
              onClick={() => setTags((ts) => (ts.includes(t) ? ts.filter((x) => x !== t) : [...ts, t]))}>{t}</button>
          ))}
        </div>
        <textarea className="meals-input" rows={3} maxLength={1000} aria-label="Notes"
          placeholder="Notes for next time, e.g. halve the Biscoff" value={notes} onChange={(e) => setNotes(e.target.value)} />
      </section>

      <section className="meals-form-block">
        <h3>Taken out of the kitchen</h3>
        {deductions == null ? <p className="meals-muted meals-small">Working it out…</p>
          : inKitchen.length === 0 ? <p className="meals-muted meals-small">None of this recipe's ingredients are recorded in your kitchen, so nothing changes.</p>
          : (
            <ul className="meals-deductions">
              {inKitchen.map((d) => (
                <li key={d.name} className="meals-deduction">
                  <span className="meals-deduction__name">{d.name}</span>
                  {d.tier === 'tracked' && d.amount != null ? (
                    <span className="meals-deduction__qty">
                      −<input className="meals-input meals-input--qty" type="number" min={0} value={Math.round(d.amount)}
                        aria-label={`${d.name} used`} onChange={(e) => setD(d.name, { amount: Number(e.target.value) })} />
                      <span className="meals-muted meals-small">{d.unit === 'each' ? '' : d.unit}</span>
                    </span>
                  ) : <span className="meals-muted meals-small">{d.tier === 'staple' ? 'cupboard' : formatQty(d.amount, d.unit) || 'some'}</span>}
                  <label className="meals-toggle meals-toggle--small">
                    <input type="checkbox" checked={d.ranOut} onChange={(e) => setD(d.name, { ranOut: e.target.checked })} />
                    <span>ran out</span>
                  </label>
                </li>
              ))}
            </ul>
          )}
      </section>

      {cooked.error && <p className="meals-error" role="alert">Could not save: {cooked.error.message}</p>}
      <div className="meals-row">
        <button type="button" className="meals-button meals-button--primary" disabled={score === 0 || cooked.isPending || deductions == null} onClick={save}>
          {cooked.isPending ? 'Saving…' : 'Save'}
        </button>
        <button type="button" className="meals-button" onClick={onClose}>Cancel</button>
      </div>
    </Sheet>
  )
}
