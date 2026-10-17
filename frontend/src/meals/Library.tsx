import { useMemo, useState } from 'react'
import { Search } from 'lucide-react'

import { averageScore, BOOKS } from './logic'
import { RecipeCard } from './ui'
import type { Category, Rating, Recipe } from './types'

const CATEGORIES: { key: Category | 'all'; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'breakfast', label: 'Breakfast' },
  { key: 'main', label: 'Mains' },
  { key: 'snack', label: 'Snacks' },
  { key: 'dessert', label: 'Sweet' },
  { key: 'drink', label: 'Drinks' },
]

type Sort = 'rating' | 'protein' | 'kcal' | 'az'

export function Library({ recipes, ratings }: { recipes: Recipe[]; ratings: Rating[] }) {
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<Category | 'all'>('all')
  const [book, setBook] = useState('all')
  const [veg, setVeg] = useState(false)
  const [freezes, setFreezes] = useState(false)
  const [prep, setPrep] = useState(false)
  const [sort, setSort] = useState<Sort>('az')
  const books = useMemo(() => [...new Set(recipes.map((r) => r.source.book).filter(Boolean))] as string[], [recipes])

  const rows = useMemo(() => {
    const terms = query.toLowerCase().split(/\s+/).filter(Boolean)
    return recipes
      .filter((r) => category === 'all' || r.category === category)
      .filter((r) => book === 'all' || r.source.book === book)
      .filter((r) => !veg || r.vegetarian)
      .filter((r) => !freezes || r.freezable)
      .filter((r) => !prep || r.servings >= 3)
      .filter((r) => terms.every((t) => r.name.toLowerCase().includes(t) || r.ingredients.some((l) => l.name.includes(t))))
      .map((r) => ({ r, rating: averageScore(r.id, ratings) }))
      .sort((a, b) => {
        switch (sort) {
          case 'protein': return b.r.perServing.protein - a.r.perServing.protein
          case 'kcal': return a.r.perServing.kcal - b.r.perServing.kcal
          case 'rating': return (b.rating ?? 0) - (a.rating ?? 0) || a.r.name.localeCompare(b.r.name)
          default: return a.r.name.localeCompare(b.r.name)
        }
      })
  }, [recipes, ratings, query, category, book, veg, freezes, prep, sort])

  return (
    <div className="meals-page">
      <header className="meals-page__header">
        <h1>Recipes</h1>
        <p className="meals-muted">{recipes.length} recipes. Search a name or an ingredient.</p>
      </header>

      <label className="meals-search">
        <Search size={18} aria-hidden="true" />
        <input className="meals-search__input" placeholder="chicken, oats, burrito…" value={query}
          onChange={(e) => setQuery(e.target.value)} aria-label="Search recipes" />
      </label>

      <div className="meals-chips">
        {CATEGORIES.map((c) => (
          <button key={c.key} type="button" aria-pressed={category === c.key}
            className={`meals-chip ${category === c.key ? 'meals-chip--on' : ''}`} onClick={() => setCategory(c.key)}>{c.label}</button>
        ))}
      </div>
      <div className="meals-chips">
        <button type="button" aria-pressed={veg} className={`meals-chip ${veg ? 'meals-chip--on' : ''}`} onClick={() => setVeg(!veg)}>Vegetarian</button>
        <button type="button" aria-pressed={freezes} className={`meals-chip ${freezes ? 'meals-chip--on' : ''}`} onClick={() => setFreezes(!freezes)}>Freezes</button>
        <button type="button" aria-pressed={prep} className={`meals-chip ${prep ? 'meals-chip--on' : ''}`} onClick={() => setPrep(!prep)}>Makes 3+</button>
        <select className="meals-chip meals-chip--select" value={book} onChange={(e) => setBook(e.target.value)} aria-label="Book">
          <option value="all">All books</option>
          {books.map((b) => <option key={b} value={b}>{BOOKS[b] ?? b}</option>)}
        </select>
        <select className="meals-chip meals-chip--select" value={sort} onChange={(e) => setSort(e.target.value as Sort)} aria-label="Sort">
          <option value="az">A–Z</option>
          <option value="rating">Your rating</option>
          <option value="protein">Most protein</option>
          <option value="kcal">Fewest kcal</option>
        </select>
      </div>

      <p className="meals-muted meals-small">{rows.length} shown</p>
      <div className="meals-grid">
        {rows.map(({ r, rating }) => (
          <RecipeCard key={r.id} recipe={r}>
            {rating != null && <span className="meals-rating-pill">★ {rating.toFixed(1)}</span>}
          </RecipeCard>
        ))}
      </div>
    </div>
  )
}
