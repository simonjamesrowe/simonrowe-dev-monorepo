import { useState } from 'react'

import { useRate } from './data'
import { TAGS } from './logic'
import { Sheet, Stars } from './ui'
import type { Recipe } from './types'

/** The one log the app keeps: how a recipe went, and notes for next time. */
export function CookedSheet({ recipe, onClose, onSaved }: { recipe: Recipe; onClose: () => void; onSaved: () => void }) {
  const [score, setScore] = useState(0)
  const [again, setAgain] = useState(true)
  const [tags, setTags] = useState<string[]>([])
  const [notes, setNotes] = useState('')
  const rate = useRate()

  function save() {
    rate.mutate({ recipeId: recipe.id, score, again, tags, notes: notes.trim() }, { onSuccess: onSaved })
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
      {rate.error && <p className="meals-error" role="alert">Could not save: {rate.error.message}</p>}
      <div className="meals-row">
        <button type="button" className="meals-button meals-button--primary" disabled={score === 0 || rate.isPending} onClick={save}>
          {rate.isPending ? 'Saving…' : 'Save'}
        </button>
        <button type="button" className="meals-button" onClick={onClose}>Cancel</button>
      </div>
    </Sheet>
  )
}
