import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Heart, Plus, Search, SlidersHorizontal, X } from 'lucide-react'
import { Link, useLocation, useNavigationType, useSearchParams } from 'react-router-dom'

import { useCoverage, useFavourites, useKitchenHasFood } from './data'
import { averageScore } from './logic'
import { CoverageBar, Empty, RecipeCard, Sheet } from './ui'
import type { Category, Rating, Recipe } from './types'

const CATEGORIES: { key: Category | 'all'; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'breakfast', label: 'Breakfast' },
  { key: 'main', label: 'Mains' },
  { key: 'snack', label: 'Snacks' },
  { key: 'dessert', label: 'Sweet' },
  { key: 'drink', label: 'Drinks' },
]

const KCAL_LIMITS = [400, 500, 600, 700]
const PROTEIN_MINIMUMS = [20, 30, 40]

type Sort = 'az' | 'rating' | 'protein' | 'kcal' | 'cookable'
type Diet = 'any' | 'veg' | 'vegan'

const SORTS: { key: Sort; label: string }[] = [
  { key: 'az', label: 'A–Z' },
  { key: 'rating', label: 'Your rating' },
  { key: 'protein', label: 'Most protein' },
  { key: 'kcal', label: 'Fewest kcal' },
  { key: 'cookable', label: 'Most cookable now' },
]

/**
 * Everything the list is narrowed by. It lives in the address, not in component state, so that
 * opening a recipe and coming back returns to the same search, filters and sort.
 */
interface Filters {
  q: string
  cat: Category | 'all'
  fav: boolean
  diet: Diet
  proteins: string[]
  kcal: number | null
  minProtein: number | null
  freezes: boolean
  batch: boolean
  sort: Sort
}

function number(value: string | null): number | null {
  const n = value == null ? NaN : Number(value)
  return Number.isFinite(n) && n > 0 ? n : null
}

function readFilters(params: URLSearchParams): Filters {
  const cat = params.get('cat')
  const diet = params.get('diet')
  const sort = params.get('sort')
  return {
    q: params.get('q') ?? '',
    cat: CATEGORIES.some((c) => c.key === cat) ? cat as Category : 'all',
    fav: params.get('fav') === '1',
    diet: diet === 'veg' || diet === 'vegan' ? diet : 'any',
    proteins: params.getAll('protein'),
    kcal: number(params.get('kcal')),
    minProtein: number(params.get('protein-min')),
    freezes: params.get('freezes') === '1',
    batch: params.get('batch') === '1',
    sort: SORTS.some((s) => s.key === sort) ? sort as Sort : 'az',
  }
}

function writeFilters(f: Filters): URLSearchParams {
  const p = new URLSearchParams()
  if (f.q) p.set('q', f.q)
  if (f.cat !== 'all') p.set('cat', f.cat)
  if (f.fav) p.set('fav', '1')
  if (f.diet !== 'any') p.set('diet', f.diet)
  for (const protein of f.proteins) p.append('protein', protein)
  if (f.kcal) p.set('kcal', String(f.kcal))
  if (f.minProtein) p.set('protein-min', String(f.minProtein))
  if (f.freezes) p.set('freezes', '1')
  if (f.batch) p.set('batch', '1')
  if (f.sort !== 'az') p.set('sort', f.sort)
  return p
}

/** The filters that live in the sheet, as removable chips. Search, meal and hearts are on the page. */
function sheetChips(f: Filters): { label: string; clear: Partial<Filters> }[] {
  const chips: { label: string; clear: Partial<Filters> }[] = []
  if (f.diet !== 'any') chips.push({ label: f.diet === 'vegan' ? 'Vegan' : 'Vegetarian', clear: { diet: 'any' } })
  for (const p of f.proteins) chips.push({ label: capitalise(p), clear: { proteins: f.proteins.filter((x) => x !== p) } })
  if (f.kcal) chips.push({ label: `Under ${f.kcal} kcal`, clear: { kcal: null } })
  if (f.minProtein) chips.push({ label: `${f.minProtein}g+ protein`, clear: { minProtein: null } })
  if (f.freezes) chips.push({ label: 'Freezes', clear: { freezes: false } })
  if (f.batch) chips.push({ label: 'Makes 3+', clear: { batch: false } })
  return chips
}

function capitalise(s: string): string {
  return s.slice(0, 1).toUpperCase() + s.slice(1)
}

function matches(r: Recipe, f: Filters, terms: string[], favourites: Set<string>): boolean {
  if (f.cat !== 'all' && r.category !== f.cat) return false
  if (f.fav && !favourites.has(r.id)) return false
  if (f.diet === 'veg' && !r.vegetarian) return false
  if (f.diet === 'vegan' && !r.vegan) return false
  if (f.proteins.length && !(r.mainProtein && f.proteins.includes(r.mainProtein.toLowerCase()))) return false
  if (f.kcal && r.perServing.kcal > f.kcal) return false
  if (f.minProtein && r.perServing.protein < f.minProtein) return false
  if (f.freezes && !r.freezable) return false
  if (f.batch && r.servings < 3) return false
  return terms.every((t) => r.name.toLowerCase().includes(t)
    || (r.mainProtein ?? '').toLowerCase().includes(t)
    || (r.section ?? '').toLowerCase().includes(t)
    || r.ingredients.some((l) => l.name.includes(t)))
}

const SCROLL_KEY = 'meals-recipes-scroll'

/**
 * Puts the page back where it was when the reader returns with Back. The position is kept with
 * the search it belongs to, so a different search starts at the top.
 */
function useScrollMemory() {
  const { search } = useLocation()
  const navigation = useNavigationType()
  const searchRef = useRef(search)
  searchRef.current = search

  useLayoutEffect(() => {
    if (navigation !== 'POP') return
    try {
      const saved = JSON.parse(sessionStorage.getItem(SCROLL_KEY) ?? 'null') as { search: string; y: number } | null
      if (saved && saved.search === search) window.scrollTo(0, saved.y)
    } catch {
      // storage unavailable or garbled: start at the top
    }
    // Only on arrival: later changes to the search are the reader's own.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Saved as the reader scrolls, not on leaving: by the time this page unmounts, an effect
  // re-run (StrictMode, or a remount) could record a position the reader never left from.
  useEffect(() => {
    let frame = 0
    const onScroll = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        try {
          sessionStorage.setItem(SCROLL_KEY, JSON.stringify({ search: searchRef.current, y: window.scrollY }))
        } catch {
          // storage unavailable: nothing to remember
        }
      })
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      cancelAnimationFrame(frame)
    }
  }, [])
}

export function Library({ recipes, ratings }: { recipes: Recipe[]; ratings: Rating[] }) {
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params)
  const [sheet, setSheet] = useState(false)
  const favourites = useFavourites()
  const { hasFood } = useKitchenHasFood()
  const coverage = useCoverage().data
  useScrollMemory()

  const update = (patch: Partial<Filters>) => setParams(writeFilters({ ...filters, ...patch }), { replace: true })

  const proteins = useMemo(() => {
    const counts = new Map<string, number>()
    for (const r of recipes) {
      const p = r.mainProtein?.toLowerCase()
      if (p) counts.set(p, (counts.get(p) ?? 0) + 1)
    }
    return [...counts].sort((a, b) => b[1] - a[1]).map(([p]) => p)
  }, [recipes])

  const sort = filters.sort === 'cookable' && !hasFood ? 'az' : filters.sort
  const rows = useMemo(() => {
    const terms = filters.q.toLowerCase().split(/\s+/).filter(Boolean)
    return recipes
      .filter((r) => matches(r, filters, terms, favourites.ids))
      .map((r) => ({ r, rating: averageScore(r.id, ratings) }))
      .sort((a, b) => {
        switch (sort) {
          case 'protein': return b.r.perServing.protein - a.r.perServing.protein
          case 'kcal': return a.r.perServing.kcal - b.r.perServing.kcal
          case 'rating': return (b.rating ?? 0) - (a.rating ?? 0) || a.r.name.localeCompare(b.r.name)
          case 'cookable': return (coverage?.[b.r.id]?.ratio ?? 0) - (coverage?.[a.r.id]?.ratio ?? 0) || a.r.name.localeCompare(b.r.name)
          default: return a.r.name.localeCompare(b.r.name)
        }
      })
    // filters is rebuilt from params on every render; params is what changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recipes, ratings, params, favourites.ids, coverage, sort])

  const chips = sheetChips(filters)
  const anything = writeFilters({ ...filters, sort: 'az' }).toString() !== ''
  const clearAll = () => setParams(writeFilters({ ...readFilters(new URLSearchParams()), sort: filters.sort }), { replace: true })

  return (
    <div className="meals-page">
      <header className="meals-page__header">
        <div className="meals-section__head">
          <h1>Recipes</h1>
          <Link className="meals-button meals-button--small" to="/recipes/new"><Plus size={14} /> Import a recipe</Link>
        </div>
      </header>

      <div className="meals-find">
        <label className="meals-search">
          <Search size={18} aria-hidden="true" />
          <input className="meals-search__input" placeholder="Search: chicken, oats, curry…" value={filters.q}
            onChange={(e) => update({ q: e.target.value })} aria-label="Search recipes" />
          {filters.q && (
            <button type="button" className="meals-icon-button meals-icon-button--small" aria-label="Clear search"
              onClick={() => update({ q: '' })}><X size={16} /></button>
          )}
        </label>
        <button type="button" className={`meals-button meals-find__filters ${chips.length ? 'meals-button--on' : ''}`}
          onClick={() => setSheet(true)}>
          <SlidersHorizontal size={16} aria-hidden="true" /> Filters{chips.length > 0 && <span className="meals-count">{chips.length}</span>}
        </button>
      </div>

      <div className="meals-chips meals-chips--scroll" role="group" aria-label="Meal">
        <button type="button" aria-pressed={filters.fav}
          className={`meals-chip meals-chip--heart ${filters.fav ? 'meals-chip--on' : ''}`} onClick={() => update({ fav: !filters.fav })}>
          <Heart size={14} fill={filters.fav ? 'currentColor' : 'none'} aria-hidden="true" /> Favourites
        </button>
        {CATEGORIES.map((c) => (
          <button key={c.key} type="button" aria-pressed={filters.cat === c.key}
            className={`meals-chip ${filters.cat === c.key ? 'meals-chip--on' : ''}`} onClick={() => update({ cat: c.key })}>{c.label}</button>
        ))}
      </div>

      {chips.length > 0 && (
        <div className="meals-chips" aria-label="Active filters">
          {chips.map((c) => (
            <button key={c.label} type="button" className="meals-chip meals-chip--active" onClick={() => update(c.clear)}
              aria-label={`Remove ${c.label}`}>
              {c.label} <X size={13} aria-hidden="true" />
            </button>
          ))}
        </div>
      )}

      <div className="meals-results-bar">
        <p className="meals-muted meals-small" role="status">
          {rows.length === recipes.length ? `${recipes.length} recipes` : `${rows.length} of ${recipes.length} recipes`}
          {anything && <> · <button type="button" className="meals-link" onClick={clearAll}>Clear all</button></>}
        </p>
        <label className="meals-sort">
          <span className="meals-muted meals-small">Sort</span>
          <select className="meals-chip meals-chip--select" value={sort} onChange={(e) => update({ sort: e.target.value as Sort })} aria-label="Sort">
            {SORTS.filter((s) => s.key !== 'cookable' || hasFood).map((s) => <option key={s.key} value={s.key}>{s.label}</option>)}
          </select>
        </label>
      </div>

      {rows.length === 0 ? (
        filters.fav && favourites.ids.size === 0 ? (
          <Empty icon={<Heart size={32} />} title="No favourites yet">
            Tap the heart on any recipe to keep it here. Your hearts are yours alone.
          </Empty>
        ) : (
          <Empty icon={<Search size={32} />} title="Nothing matches">
            <button type="button" className="meals-button" onClick={clearAll}>Clear filters</button>
          </Empty>
        )
      ) : (
        <div className="meals-grid">
          {rows.map(({ r, rating }) => (
            <RecipeCard key={r.id} recipe={r}>
              {(hasFood || rating != null) && (
                <span className="meals-card__foot">
                  {hasFood ? <CoverageBar coverage={coverage?.[r.id]} /> : <span />}
                  {rating != null && <span className="meals-rating-pill">★ {rating.toFixed(1)}</span>}
                </span>
              )}
            </RecipeCard>
          ))}
        </div>
      )}

      {sheet && (
        <Sheet title="Filters" onClose={() => setSheet(false)}>
          <FilterGroup label="Diet">
            {(['any', 'veg', 'vegan'] as Diet[]).map((d) => (
              <button key={d} type="button" aria-pressed={filters.diet === d}
                className={`meals-chip ${filters.diet === d ? 'meals-chip--on' : ''}`} onClick={() => update({ diet: d })}>
                {d === 'any' ? 'Anything' : d === 'veg' ? 'Vegetarian' : 'Vegan'}
              </button>
            ))}
          </FilterGroup>
          {proteins.length > 0 && (
            <FilterGroup label="Main protein">
              {proteins.map((p) => {
                const on = filters.proteins.includes(p)
                return (
                  <button key={p} type="button" aria-pressed={on} className={`meals-chip ${on ? 'meals-chip--on' : ''}`}
                    onClick={() => update({ proteins: on ? filters.proteins.filter((x) => x !== p) : [...filters.proteins, p] })}>
                    {capitalise(p)}
                  </button>
                )
              })}
            </FilterGroup>
          )}
          <FilterGroup label="Calories per serving">
            <button type="button" aria-pressed={!filters.kcal} className={`meals-chip ${!filters.kcal ? 'meals-chip--on' : ''}`}
              onClick={() => update({ kcal: null })}>Any</button>
            {KCAL_LIMITS.map((k) => (
              <button key={k} type="button" aria-pressed={filters.kcal === k}
                className={`meals-chip ${filters.kcal === k ? 'meals-chip--on' : ''}`} onClick={() => update({ kcal: k })}>Under {k}</button>
            ))}
          </FilterGroup>
          <FilterGroup label="Protein per serving">
            <button type="button" aria-pressed={!filters.minProtein} className={`meals-chip ${!filters.minProtein ? 'meals-chip--on' : ''}`}
              onClick={() => update({ minProtein: null })}>Any</button>
            {PROTEIN_MINIMUMS.map((g) => (
              <button key={g} type="button" aria-pressed={filters.minProtein === g}
                className={`meals-chip ${filters.minProtein === g ? 'meals-chip--on' : ''}`} onClick={() => update({ minProtein: g })}>{g}g+</button>
            ))}
          </FilterGroup>
          <FilterGroup label="Good for">
            <button type="button" aria-pressed={filters.freezes} className={`meals-chip ${filters.freezes ? 'meals-chip--on' : ''}`}
              onClick={() => update({ freezes: !filters.freezes })}>Freezes</button>
            <button type="button" aria-pressed={filters.batch} className={`meals-chip ${filters.batch ? 'meals-chip--on' : ''}`}
              onClick={() => update({ batch: !filters.batch })}>Makes 3+ portions</button>
          </FilterGroup>
          <div className="meals-row meals-sheet__actions">
            <button type="button" className="meals-button" onClick={() => update({ diet: 'any', proteins: [], kcal: null, minProtein: null, freezes: false, batch: false })}>Reset</button>
            <button type="button" className="meals-button meals-button--primary" onClick={() => setSheet(false)}>
              Show {rows.length} recipe{rows.length === 1 ? '' : 's'}
            </button>
          </div>
        </Sheet>
      )}
    </div>
  )
}

function FilterGroup({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <fieldset className="meals-filter-group">
      <legend>{label}</legend>
      <div className="meals-chips">{children}</div>
    </fieldset>
  )
}
