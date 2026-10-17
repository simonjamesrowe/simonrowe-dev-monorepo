import { useMemo, useState } from 'react'
import { BookOpen } from 'lucide-react'
import { Link } from 'react-router-dom'

import { defaultSlot, homeSections, MEAL_SLOTS, type MealSlot } from './logic'
import { Empty, RecipeCard } from './ui'
import type { Rating, Recipe } from './types'

export function Home({ recipes, ratings, now }: { recipes: Recipe[]; ratings: Rating[]; now?: Date }) {
  // Fixed for the page's life, so the daily "something new" order doesn't reshuffle on render.
  const [today] = useState(() => now ?? new Date())
  const [slot, setSlot] = useState<MealSlot>(() => defaultSlot(today))
  const sections = useMemo(() => homeSections(recipes, ratings, slot, today), [recipes, ratings, slot, today])

  if (recipes.length === 0) {
    return (
      <div className="meals-page">
        <Empty icon={<BookOpen size={32} />} title="The library is empty">
          Upload the recipe bundles on the <Link to="/import">Import</Link> page.
        </Empty>
      </div>
    )
  }

  return (
    <div className="meals-page">
      <header className="meals-page__header">
        <p className="meals-eyebrow">{today.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' })}</p>
        <h1>What shall I eat?</h1>
      </header>

      <div className="meals-segmented" role="tablist" aria-label="Meal">
        {MEAL_SLOTS.map((s) => (
          <button key={s.key} type="button" role="tab" aria-selected={slot === s.key}
            className={slot === s.key ? 'meals-segmented__on' : ''} onClick={() => setSlot(s.key)}>{s.label}</button>
        ))}
      </div>

      <Section title="Your favourites" empty="Rate what you cook and your favourites show up here." recipes={sections.favourites} />
      <Section title="Something new" empty="You've tried everything here. Impressive." recipes={sections.untried} />
      {sections.recent.length > 0 && <Section title="Cooked lately" empty="" recipes={sections.recent} />}
    </div>
  )
}

function Section({ title, empty, recipes }: { title: string; empty: string; recipes: Recipe[] }) {
  return (
    <section className="meals-section">
      <h2 className="meals-section__title">{title}</h2>
      {recipes.length === 0
        ? <p className="meals-muted meals-small">{empty}</p>
        : <div className="meals-strip">{recipes.map((r) => <RecipeCard key={r.id} recipe={r} />)}</div>}
    </section>
  )
}
