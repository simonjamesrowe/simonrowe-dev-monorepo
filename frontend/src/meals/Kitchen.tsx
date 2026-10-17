import { useMemo, useState } from 'react'
import { AlarmClock, Plus, Refrigerator, Trash2 } from 'lucide-react'

import { useHistory, useKitchen, useMine } from './data'
import { daysUntil, describeUseBy } from './logic'
import { QuickAdd } from './QuickAdd'
import { Empty, Loading } from './ui'
import type { Ingredient, InventoryChange, InventoryItem, StapleStatus } from './types'

/** What's in: fresh food by quantity and use-by date, cupboard staples as have / low / out. */
export function Kitchen({ ingredients }: { ingredients: Map<string, Ingredient> }) {
  const kitchen = useKitchen()
  const history = useHistory()
  const [tab, setTab] = useState<'tracked' | 'staple'>('tracked')
  const [adding, setAdding] = useState('')
  const change = useMine((api, changes: InventoryChange[]) => api.applyChanges(changes))
  const remove = useMine((api, name: string) => api.removeItem(name))

  const items = useMemo(() => kitchen.data ?? [], [kitchen.data])
  const tracked = items.filter((i) => i.tier === 'tracked')
    .sort((a, b) => (daysUntil(a.useBy) ?? 999) - (daysUntil(b.useBy) ?? 999) || a.name.localeCompare(b.name))
  const staples = items.filter((i) => i.tier === 'staple')
  const expiring = tracked.filter((i) => (i.quantity ?? 0) > 0 && (daysUntil(i.useBy) ?? 99) <= 2)
  const options = [...ingredients.values()].filter((i) => !i.assumed && !items.some((x) => x.name === i.name))

  function addIngredient() {
    const name = adding.trim().toLowerCase()
    if (!name) return
    const tier = ingredients.get(name)?.tier ?? 'tracked'
    change.mutate([tier === 'staple'
      ? { name, action: 'status', status: 'have' }
      : { name, action: 'add', quantity: 1, unit: ingredients.get(name)?.unit ?? 'each' }])
    setTab(tier)
    setAdding('')
  }

  const setQuantity = (i: InventoryItem, quantity: number) =>
    change.mutate([{ name: i.name, action: 'set', quantity, unit: i.unit }])
  const setUseBy = (i: InventoryItem, useBy: string) =>
    change.mutate([{ name: i.name, action: 'set', quantity: i.quantity ?? 0, unit: i.unit, useBy: useBy || null }])
  const setStatus = (i: InventoryItem, status: StapleStatus) =>
    change.mutate([{ name: i.name, action: 'status', status }])

  return (
    <div className="meals-page">
      <header className="meals-page__header">
        <h1>Kitchen</h1>
        <p className="meals-muted">What's in. Cooking takes things off; deliveries put them back.</p>
      </header>

      <section className="meals-panel">
        <h2 className="meals-panel__title">Quick add</h2>
        <QuickAdd mode="note" />
      </section>

      {expiring.length > 0 && (
        <p className="meals-alert"><AlarmClock size={18} aria-hidden="true" /> Use soon: {expiring.map((i) => i.name).join(', ')}</p>
      )}

      <div className="meals-segmented" role="tablist" aria-label="Kind">
        <button type="button" role="tab" aria-selected={tab === 'tracked'} className={tab === 'tracked' ? 'meals-segmented__on' : ''}
          onClick={() => setTab('tracked')}>Fresh &amp; protein ({tracked.length})</button>
        <button type="button" role="tab" aria-selected={tab === 'staple'} className={tab === 'staple' ? 'meals-segmented__on' : ''}
          onClick={() => setTab('staple')}>Cupboard ({staples.length})</button>
      </div>

      <div className="meals-row meals-row--fill">
        <input className="meals-input" list="meals-ingredient-options" placeholder="Add an ingredient…" aria-label="Add an ingredient"
          value={adding} onChange={(e) => setAdding(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') addIngredient() }} />
        <datalist id="meals-ingredient-options">{options.map((i) => <option key={i.name} value={i.name} />)}</datalist>
        <button type="button" className="meals-button" onClick={addIngredient} disabled={!adding.trim()}><Plus size={16} /> Add</button>
      </div>
      {change.error && <p className="meals-error" role="alert">{change.error.message}</p>}

      {kitchen.isLoading ? <Loading /> : tab === 'tracked' ? (
        tracked.length === 0
          ? <Empty icon={<Refrigerator size={32} />} title="Nothing fresh recorded">Quick add what's in the fridge, or put a delivery in from the Shop page.</Empty>
          : (
            <ul className="meals-inventory">
              {tracked.map((i) => {
                const label = describeUseBy(i.useBy)
                return (
                  <li key={i.name} className={`meals-inventory__item ${(i.quantity ?? 0) <= 0 ? 'meals-inventory__item--empty' : ''}`}>
                    <span className="meals-inventory__name">{i.name}</span>
                    <span className="meals-inventory__qty">
                      <input key={`${i.name}-${i.quantity}`} className="meals-input meals-input--qty" type="number" inputMode="decimal" min={0}
                        defaultValue={i.quantity ?? 0} aria-label={`${i.name} quantity`}
                        onBlur={(e) => { const v = Number(e.target.value); if (v !== (i.quantity ?? 0)) setQuantity(i, v) }} />
                      <span className="meals-muted meals-small">{i.unit ?? ''}</span>
                    </span>
                    <span className="meals-inventory__useby">
                      <input className="meals-input meals-input--date" type="date" value={i.useBy ?? ''}
                        aria-label={`${i.name} use by`} onChange={(e) => setUseBy(i, e.target.value)} />
                      {label && <span className={`meals-useby meals-useby--${label.tone || 'later'}`}>{label.text}</span>}
                    </span>
                    <button type="button" className="meals-icon-button" aria-label={`Remove ${i.name}`} onClick={() => remove.mutate(i.name)}><Trash2 size={16} /></button>
                  </li>
                )
              })}
            </ul>
          )
      ) : (
        staples.length === 0
          ? <Empty icon={<Refrigerator size={32} />} title="No cupboard staples yet">Oats, spices, sauces and powders live here as have, low or out.</Empty>
          : (
            <ul className="meals-inventory">
              {staples.map((i) => (
                <li key={i.name} className="meals-inventory__item">
                  <span className="meals-inventory__name">{i.name}</span>
                  <span className="meals-tri" role="radiogroup" aria-label={`${i.name} status`}>
                    {(['have', 'low', 'out'] as StapleStatus[]).map((s) => (
                      <button key={s} type="button" role="radio" aria-checked={i.status === s}
                        className={`meals-tri__option meals-tri__option--${s} ${i.status === s ? 'meals-tri__option--on' : ''}`}
                        onClick={() => setStatus(i, s)}>{s}</button>
                    ))}
                  </span>
                  <button type="button" className="meals-icon-button" aria-label={`Remove ${i.name}`} onClick={() => remove.mutate(i.name)}><Trash2 size={16} /></button>
                </li>
              ))}
            </ul>
          )
      )}

      {(history.data?.length ?? 0) > 0 && (
        <section className="meals-panel meals-panel--quiet">
          <h2 className="meals-panel__title">Recent changes</h2>
          <ul className="meals-events">
            {history.data!.slice(0, 10).map((e) => (
              <li key={e.id}>
                <span className="meals-muted meals-small">{new Date(e.at).toLocaleString('en-GB', { weekday: 'short', hour: '2-digit', minute: '2-digit' })}</span>{' '}
                {e.summary}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}
