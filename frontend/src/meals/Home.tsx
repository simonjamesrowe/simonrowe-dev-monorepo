import { useState } from 'react'
import { AlarmClock, BookOpen, Check, ListPlus, Sparkles } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useKitchen, useMine, useSuggestions, useUpNext } from './data'
import { daysUntil, defaultSlot, MEAL_SLOTS } from './logic'
import { QuickAdd } from './QuickAdd'
import { CoverageBar, Empty, Loading, Photo } from './ui'
import type { MealSlot, Recipe, Suggestion } from './types'

/** "What shall I eat?": what the kitchen can make now, and what is one or two things short. */
export function Home({ recipes, byId, now, canImport = true }: {
  recipes: Recipe[]; byId: Map<string, Recipe>; now?: Date; canImport?: boolean
}) {
  const [today] = useState(() => now ?? new Date())
  const [slot, setSlot] = useState<MealSlot>(() => defaultSlot(today))
  const suggestions = useSuggestions(slot)
  const kitchen = useKitchen()
  const upNext = useUpNext()
  const queue = useMine((api, id: string) => api.queue(id))

  if (recipes.length === 0) {
    return (
      <div className="meals-page">
        <Empty icon={<BookOpen size={32} />} title="The library is empty">
          {canImport
            ? <>Upload the recipe bundles on the <Link to="/more">More</Link> page.</>
            : <>No recipes have been loaded yet.</>}
        </Empty>
      </div>
    )
  }

  const kitchenEmpty = (kitchen.data?.length ?? 0) === 0
  const expiring = (kitchen.data ?? []).filter((i) => i.tier === 'tracked' && (i.quantity ?? 0) > 0
    && (daysUntil(i.useBy, today) ?? 99) <= 2)
  const queued = new Set((upNext.data ?? []).map((u) => u.recipeId))
  const all = (suggestions.data ?? []).filter((s) => byId.has(s.recipeId))
  const toGet = (s: Suggestion) => s.coverage.missing.length + s.coverage.shortOf.length
  const ready = all.filter((s) => toGet(s) === 0).slice(0, 8)
  const close = all.filter((s) => toGet(s) >= 1 && toGet(s) <= 2).slice(0, 8)

  return (
    <div className="meals-page">
      <header className="meals-page__header">
        <p className="meals-eyebrow">{today.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' })}</p>
        <h1>What shall I eat?</h1>
      </header>

      {kitchen.isSuccess && kitchenEmpty && (
        <section className="meals-panel meals-panel--ai">
          <h2 className="meals-panel__title"><Sparkles size={18} aria-hidden="true" /> Tell me what's in</h2>
          <p className="meals-muted meals-small">Suggestions come from what you have. Say it the way you'd text it, or take a photo.</p>
          <QuickAdd mode="note" />
        </section>
      )}

      {expiring.length > 0 && (
        <p className="meals-alert"><AlarmClock size={18} aria-hidden="true" /> Use soon: {expiring.map((i) => i.name).join(', ')}</p>
      )}

      {(upNext.data?.length ?? 0) > 0 && (
        <section className="meals-section">
          <div className="meals-section__head">
            <h2 className="meals-section__title">Up next</h2>
            <Link className="meals-link" to="/up-next">All {upNext.data!.length}</Link>
          </div>
          <div className="meals-mini-strip">
            {upNext.data!.map((u) => byId.get(u.recipeId)).filter(Boolean).map((r) => (
              <Link key={r!.id} className="meals-mini-card" to={`/recipes/${r!.id}`}>
                <Photo recipe={r!} /><span>{r!.name}</span>
              </Link>
            ))}
          </div>
        </section>
      )}

      <div className="meals-segmented" role="tablist" aria-label="Meal">
        {MEAL_SLOTS.map((s) => (
          <button key={s.key} type="button" role="tab" aria-selected={slot === s.key}
            className={slot === s.key ? 'meals-segmented__on' : ''} onClick={() => setSlot(s.key)}>{s.label}</button>
        ))}
      </div>

      {suggestions.isLoading ? <Loading /> : (
        <>
          <Group title="You can cook these now" items={ready} byId={byId} queued={queued} onQueue={(id) => queue.mutate(id)}
            empty={kitchenEmpty ? "Add what's in your kitchen to see these." : 'Nothing you have everything for. See the near misses below.'} />
          <Group title="One or two things short" items={close} byId={byId} queued={queued} onQueue={(id) => queue.mutate(id)}
            empty="Nothing close." />
        </>
      )}

      {!kitchenEmpty && (
        <section className="meals-panel meals-panel--quiet">
          <h2 className="meals-panel__title">Quick add</h2>
          <QuickAdd mode="note" />
        </section>
      )}
    </div>
  )
}

function Group({ title, items, byId, empty, queued, onQueue }: {
  title: string; items: Suggestion[]; byId: Map<string, Recipe>; empty: string; queued: Set<string>; onQueue: (id: string) => void
}) {
  return (
    <section className="meals-section">
      <h2 className="meals-section__title">{title}</h2>
      {items.length === 0 ? <p className="meals-muted meals-small">{empty}</p> : (
        <ul className="meals-suggestions">
          {items.map((s) => {
            const recipe = byId.get(s.recipeId)!
            const isQueued = queued.has(recipe.id)
            return (
              <li key={recipe.id} className="meals-suggestion">
                <Link className="meals-suggestion__open" to={`/recipes/${recipe.id}`}>
                  <Photo recipe={recipe} className="meals-suggestion__photo" />
                  <span className="meals-suggestion__body">
                    <span className="meals-suggestion__name">{recipe.name}</span>
                    <span className="meals-suggestion__why">{s.reasons.join(' · ') || `${Math.round(recipe.perServing.protein)}g protein`}</span>
                    <CoverageBar coverage={s.coverage} />
                  </span>
                </Link>
                <button type="button" className={`meals-icon-button ${isQueued ? 'meals-icon-button--on' : ''}`} disabled={isQueued}
                  aria-label={isQueued ? `${recipe.name} is in Up next` : `Add ${recipe.name} to Up next`} onClick={() => onQueue(recipe.id)}>
                  {isQueued ? <Check size={18} /> : <ListPlus size={18} />}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
