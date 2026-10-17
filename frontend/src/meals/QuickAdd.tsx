import { useState } from 'react'
import { Camera, Loader2, Sparkles, X } from 'lucide-react'

import { useMealsApi, useMine } from './data'
import { shrinkImage } from './images'
import { describeChange } from './logic'
import type { InventoryChange } from './types'

const PLACEHOLDER = {
  note: 'e.g. bought 12 eggs and 2 packs of chicken breast, used the last of the rice, running low on oats',
  order: 'Paste your Ocado, Amazon or Deliveroo order confirmation here, substitutions and all',
}

interface Props {
  mode: 'note' | 'order'
  onDone?: () => void
}

/**
 * Plain words, a pasted order or a photo in; proposed kitchen changes out. Nothing is saved
 * until the owner unticks anything wrong and confirms.
 */
export function QuickAdd({ mode, onDone }: Props) {
  const api = useMealsApi()
  const [text, setText] = useState('')
  const [photo, setPhoto] = useState<Blob | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [proposed, setProposed] = useState<(InventoryChange & { keep: boolean })[] | null>(null)
  const [fresh, setFresh] = useState<string[]>([])
  const apply = useMine((a, changes: InventoryChange[]) => a.applyChanges(changes, mode === 'order' ? 'order' : 'quick-add'))

  async function read() {
    setBusy(true)
    setError(null)
    try {
      const proposal = await api.readKitchen(text, mode, photo)
      setProposed(proposal.changes.map((c) => ({ ...c, keep: true })))
      setFresh(proposal.newIngredients)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  function confirm() {
    const chosen: InventoryChange[] = (proposed ?? []).filter((c) => c.keep)
      .map(({ name, action, quantity, unit, status, useBy, source }) => ({ name, action, quantity, unit, status, useBy, source }))
    apply.mutate(chosen, {
      onSuccess: () => {
        setProposed(null)
        setText('')
        setPhoto(null)
        onDone?.()
      },
    })
  }

  if (proposed) {
    return (
      <div className="meals-quick-add">
        {proposed.length === 0 && <p>Nothing recognisable in that. Try naming the food.</p>}
        <ul className="meals-changes">
          {proposed.map((c, i) => (
            <li key={i} className={c.keep ? '' : 'meals-changes__off'}>
              <label>
                <input type="checkbox" checked={c.keep}
                  onChange={() => setProposed((p) => p!.map((x, j) => (j === i ? { ...x, keep: !x.keep } : x)))} />
                <span>
                  <strong>{describeChange(c)}</strong>
                  {fresh.includes(c.name) && <span className="meals-tag meals-tag--new">new ingredient</span>}
                  {c.useBy && <span className="meals-tag">use by {c.useBy}</span>}
                  {c.source && <span className="meals-changes__source">“{c.source}”</span>}
                </span>
              </label>
            </li>
          ))}
        </ul>
        {apply.error && <p className="meals-error" role="alert">{apply.error.message}</p>}
        <div className="meals-row">
          <button type="button" className="meals-button meals-button--primary" disabled={apply.isPending} onClick={confirm}>
            {apply.isPending ? 'Saving…' : 'Update kitchen'}
          </button>
          <button type="button" className="meals-button" onClick={() => setProposed(null)}>Back</button>
        </div>
      </div>
    )
  }

  return (
    <div className="meals-quick-add">
      <textarea className="meals-input" rows={mode === 'order' ? 7 : 3} placeholder={PLACEHOLDER[mode]}
        aria-label={mode === 'order' ? 'Order confirmation' : 'What changed'}
        value={text} onChange={(e) => setText(e.target.value)} />
      {photo && (
        <span className="meals-tag">
          Photo attached <button type="button" className="meals-link" aria-label="Remove photo" onClick={() => setPhoto(null)}><X size={12} /></button>
        </span>
      )}
      {error && <p className="meals-error" role="alert">{error}</p>}
      <div className="meals-row">
        <button type="button" className="meals-button meals-button--ai" disabled={(!text.trim() && !photo) || busy} onClick={read}>
          {busy ? <Loader2 className="meals-spin" size={16} /> : <Sparkles size={16} />} {mode === 'order' ? 'Read order' : 'Read it'}
        </button>
        {mode === 'note' && (
          <label className="meals-button">
            <Camera size={16} /> Photo
            <input type="file" accept="image/*" capture="environment" className="meals-visually-hidden"
              onChange={async (e) => { const f = e.target.files?.[0]; if (f) setPhoto(await shrinkImage(f)); e.target.value = '' }} />
          </label>
        )}
      </div>
    </div>
  )
}
