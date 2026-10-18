import { useState } from 'react'
import { Check, Heart, ListTodo, Minus, Plus, Search, ShoppingBasket, Trash2, X } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useCoverage, useFavourites, useKitchenHasFood, useMine, useShopping, useUpNext } from './data'
import { recipeMatches, searchTerms } from './logic'
import { CoverageBar, Empty, Loading, Photo } from './ui'
import type { Recipe } from './types'

const RESULTS = 8

/**
 * What you are planning to cook. No days and no slots: find a recipe or tap a favourite to add
 * it, set the portions, and the shopping list is built from whatever is here.
 */
export function UpNext({ recipes, byId }: { recipes: Recipe[]; byId: Map<string, Recipe> }) {
  const upNext = useUpNext()
  const favourites = useFavourites()
  const shopping = useShopping()
  const coverage = useCoverage().data
  const { hasFood } = useKitchenHasFood()
  const [query, setQuery] = useState('')
  const add = useMine((api, id: string) => api.queue(id))
  const setPortions = useMine((api, v: { id: string; portions: number }) => api.queue(v.id, v.portions))
  const remove = useMine((api, id: string) => api.unqueue(id))

  const items = (upNext.data ?? []).filter((u) => byId.has(u.recipeId))
  const planned = new Set(items.map((u) => u.recipeId))
  const terms = searchTerms(query)
  const results = terms.length === 0 ? [] : recipes.filter((r) => recipeMatches(r, terms)).slice(0, RESULTS)
  const favouriteRecipes = (favourites.data ?? [])
    .map((f) => byId.get(f.recipeId))
    .filter((r): r is Recipe => !!r && !planned.has(r.id))
  const toBuy = (shopping.data?.items ?? []).filter((i) => !i.basket).length

  return (
    <div className="meals-page">
      <header className="meals-page__header">
        <h1>Up next</h1>
        <p className="meals-muted">What you're planning to cook. Your shopping list is made from it.</p>
      </header>

      <section className="meals-adder" aria-label="Add a recipe">
        <label className="meals-search">
          <Search size={18} aria-hidden="true" />
          <input className="meals-search__input" placeholder="Find a recipe to add…" value={query}
            onChange={(e) => setQuery(e.target.value)} aria-label="Find a recipe to add" />
          {query && (
            <button type="button" className="meals-icon-button meals-icon-button--small" aria-label="Clear search"
              onClick={() => setQuery('')}><X size={16} /></button>
          )}
        </label>
        {terms.length > 0 && (
          results.length === 0 ? <p className="meals-muted meals-small">No recipe matches “{query}”.</p> : (
            <ul className="meals-results" aria-label="Matching recipes">
              {results.map((r) => {
                const isPlanned = planned.has(r.id)
                return (
                  <li key={r.id} className="meals-result">
                    <Photo recipe={r} className="meals-result__photo" />
                    <span className="meals-result__body">
                      <span className="meals-result__name">{r.name}</span>
                      <span className="meals-muted meals-small">{Math.round(r.perServing.kcal)} kcal · {Math.round(r.perServing.protein)}g protein</span>
                    </span>
                    <button type="button" className={`meals-button meals-button--small ${isPlanned ? '' : 'meals-button--primary'}`}
                      disabled={isPlanned} aria-label={isPlanned ? `${r.name} is planned` : `Add ${r.name}`}
                      onClick={() => add.mutate(r.id)}>
                      {isPlanned ? <><Check size={14} /> Planned</> : <><Plus size={14} /> Add</>}
                    </button>
                  </li>
                )
              })}
            </ul>
          )
        )}
      </section>

      <section className="meals-section">
        <h2 className="meals-section__title"><Heart size={16} aria-hidden="true" /> From your favourites</h2>
        {(favourites.data?.length ?? 0) === 0 ? (
          <p className="meals-muted meals-small">
            Heart recipes on the <Link className="meals-link" to="/recipes">Recipes</Link> tab and they'll be here to add in one tap.
          </p>
        ) : favouriteRecipes.length === 0 ? (
          <p className="meals-muted meals-small">Every favourite is already planned.</p>
        ) : (
          <div className="meals-mini-strip">
            {favouriteRecipes.map((r) => (
              <button key={r.id} type="button" className="meals-mini-card meals-mini-card--add" aria-label={`Add ${r.name}`}
                onClick={() => add.mutate(r.id)}>
                <span className="meals-mini-card__photo">
                  <Photo recipe={r} />
                  <span className="meals-mini-card__plus" aria-hidden="true"><Plus size={16} /></span>
                </span>
                <span>{r.name}</span>
              </button>
            ))}
          </div>
        )}
      </section>

      <section className="meals-section">
        <h2 className="meals-section__title">Planned{items.length > 0 && ` · ${items.length}`}</h2>
        {upNext.isLoading ? <Loading /> : items.length === 0 ? (
          <Empty icon={<ListTodo size={32} />} title="Nothing planned yet">
            Search above or tap a favourite to start.
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
                      {hasFood
                        ? <CoverageBar coverage={coverage?.[recipe.id]} />
                        : <span className="meals-muted meals-small">{Math.round(recipe.perServing.kcal)} kcal · {Math.round(recipe.perServing.protein)}g protein</span>}
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
      </section>

      {items.length > 0 && (
        <div className="meals-cta">
          <Link className="meals-button meals-button--primary meals-cta__button" to="/shop">
            <ShoppingBasket size={18} aria-hidden="true" /> Shopping list
            {shopping.isSuccess && <span className="meals-cta__note">{toBuy === 0 ? 'nothing to buy' : `${toBuy} to buy`}</span>}
          </Link>
        </div>
      )}
    </div>
  )
}
