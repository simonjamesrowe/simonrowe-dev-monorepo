import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Camera, Link2, Loader2, Plus, Sparkles, Trash2 } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

import { useMealsApi } from './data'
import { shrinkImage } from './images'
import type { Category, IngredientLine, NewIngredient, Recipe, RecipeInput, Unit } from './types'

const CATEGORIES: Category[] = ['breakfast', 'main', 'snack', 'dessert', 'drink']
const AISLES = ['Fruit & veg', 'Meat & fish', 'Dairy & eggs', 'Bakery', 'Frozen', 'Cupboard', 'Other']
const PROTEINS = ['', 'chicken', 'beef', 'pork', 'fish', 'meat-free', 'egg']

function blankRecipe(): RecipeInput {
  return {
    name: '', category: 'main', section: null, servings: 1,
    perServing: { kcal: 0, protein: 0, carbs: 0, fat: 0 }, macrosEstimated: false,
    vegetarian: false, vegan: false, freezable: null, mainProtein: null,
    ingredients: [{ text: '', name: '', quantity: null, unit: null }],
    method: [], tips: [], swaps: [], sourceType: null, sourceUrl: null, photo: null, newIngredients: [],
  }
}

function toInput(r: Recipe): RecipeInput {
  return {
    name: r.name, category: r.category, section: r.section, servings: r.servings, perServing: r.perServing,
    macrosEstimated: r.macrosEstimated, vegetarian: r.vegetarian, vegan: r.vegan, freezable: r.freezable,
    mainProtein: r.mainProtein, ingredients: r.ingredients, method: r.method, tips: r.tips, swaps: r.swaps,
    sourceType: r.source.type, sourceUrl: r.source.url, photo: r.photo, newIngredients: [],
  }
}

/**
 * Adds a recipe (read from a link or a photo, or typed) or edits one. Whatever the model read is
 * only a draft: nothing is saved until the owner presses Save, and new ingredient names are added
 * to the catalogue only as reviewed here.
 */
export function RecipeEditor({ recipe, knownIngredients }: { recipe?: Recipe; knownIngredients: Set<string> }) {
  const api = useMealsApi()
  const client = useQueryClient()
  const navigate = useNavigate()
  const [draft, setDraft] = useState<RecipeInput | null>(recipe ? toInput(recipe) : null)
  const [url, setUrl] = useState('')
  const [reading, setReading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [photo, setPhoto] = useState<Blob | null>(null)

  async function read(how: 'link' | 'photo', file?: File) {
    setReading(true)
    setError(null)
    try {
      if (how === 'link') {
        setDraft(await api.readRecipeLink(url.trim()))
      } else if (file) {
        const small = await shrinkImage(file)
        setPhoto(small)
        setDraft(await api.readRecipePhoto(small))
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setReading(false)
    }
  }

  async function save() {
    if (!draft) return
    setSaving(true)
    setError(null)
    try {
      let photoName = draft.photo
      if (photo) photoName = (await api.uploadPhoto(photo)).name
      const lines = draft.ingredients
        .filter((l) => l.text.trim() || l.name.trim())
        .map((l) => ({ ...l, name: l.name.trim().toLowerCase(), text: l.text.trim() || l.name.trim() }))
      // Every name not in the catalogue goes in as reviewed, or as tracked/Other if untouched.
      const unknown = [...new Set(lines.map((l) => l.name).filter((n) => n && !knownIngredients.has(n)))]
      const input: RecipeInput = {
        ...draft,
        photo: photoName,
        ingredients: lines,
        method: draft.method.filter((m) => m.trim()),
        newIngredients: unknown.map((name) => draft.newIngredients.find((n) => n.name === name)
          ?? { name, unit: lines.find((l) => l.name === name)?.unit ?? null, tier: 'tracked', aisle: 'Other' }),
      }
      const saved = recipe ? await api.updateRecipe(recipe.id, input) : await api.createRecipe(input)
      await client.invalidateQueries({ queryKey: ['meals'] })
      navigate(`/recipes/${saved.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setSaving(false)
    }
  }

  if (!draft) {
    return (
      <div className="meals-page">
        <header className="meals-page__header">
          <h1>Import a recipe</h1>
          <p className="meals-muted">
            Paste a link to any recipe page (BBC Good Food, a blog…) or photograph a cookbook page. It reads the
            ingredients, method and macros into a draft, which takes up to a minute. Nothing is saved until you
            have checked it.
          </p>
        </header>
        <section className="meals-panel">
          <h2 className="meals-panel__title"><Link2 size={18} aria-hidden="true" /> From a link</h2>
          <div className="meals-row meals-row--fill">
            <input className="meals-input" type="url" placeholder="https://…" aria-label="Recipe address"
              value={url} onChange={(e) => setUrl(e.target.value)} />
            <button type="button" className="meals-button meals-button--ai" disabled={!url.trim() || reading} onClick={() => read('link')}>
              {reading ? <Loader2 className="meals-spin" size={16} /> : <Sparkles size={16} />} Read it
            </button>
          </div>
        </section>
        <section className="meals-panel">
          <h2 className="meals-panel__title"><Camera size={18} aria-hidden="true" /> From a photo</h2>
          <label className="meals-dropzone">
            {reading ? <Loader2 className="meals-spin" size={24} /> : <Camera size={24} aria-hidden="true" />}
            <span>{reading ? 'Reading…' : 'Choose or take a photo'}</span>
            <input type="file" accept="image/*" disabled={reading} className="meals-visually-hidden"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) void read('photo', f); e.target.value = '' }} />
          </label>
        </section>
        <button type="button" className="meals-button" onClick={() => setDraft(blankRecipe())}><Plus size={16} /> Type it in</button>
        {error && <p className="meals-error" role="alert">{error}</p>}
      </div>
    )
  }

  const set = (patch: Partial<RecipeInput>) => setDraft({ ...draft, ...patch })
  const setLine = (i: number, patch: Partial<IngredientLine>) =>
    set({ ingredients: draft.ingredients.map((l, j) => (j === i ? { ...l, ...patch } : l)) })
  const unknownNames = [...new Set(draft.ingredients.map((l) => l.name.trim().toLowerCase()).filter((n) => n && !knownIngredients.has(n)))]
  const fresh = (name: string): NewIngredient =>
    draft.newIngredients.find((n) => n.name === name) ?? { name, unit: null, tier: 'tracked', aisle: 'Other' }
  const setFresh = (name: string, patch: Partial<NewIngredient>) =>
    set({ newIngredients: [...draft.newIngredients.filter((n) => n.name !== name), { ...fresh(name), ...patch }] })

  return (
    <div className="meals-page">
      <header className="meals-page__header">
        <h1>{recipe ? `Edit ${recipe.name}` : 'Check and save'}</h1>
        {draft.sourceUrl && <p className="meals-muted meals-small">From {draft.sourceUrl}</p>}
        {draft.macrosEstimated && <p className="meals-muted meals-small">The source gave no macros, so these are estimates.</p>}
      </header>

      <div className="meals-form">
        <label className="meals-field">Name
          <input className="meals-input" value={draft.name} onChange={(e) => set({ name: e.target.value })} />
        </label>
        <div className="meals-form__row">
          <label className="meals-field">Meal
            <select className="meals-input" value={draft.category} onChange={(e) => set({ category: e.target.value as Category })}>
              {CATEGORIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </label>
          <label className="meals-field">Makes
            <input className="meals-input" type="number" min={1} max={50} value={draft.servings}
              onChange={(e) => set({ servings: Number(e.target.value) })} />
          </label>
          <label className="meals-field">Main protein
            <select className="meals-input" value={draft.mainProtein ?? ''} onChange={(e) => set({ mainProtein: e.target.value || null })}>
              {PROTEINS.map((p) => <option key={p} value={p}>{p || 'none'}</option>)}
            </select>
          </label>
        </div>
        <fieldset className="meals-form__row meals-fieldset">
          <legend>Per serving</legend>
          {(['kcal', 'protein', 'carbs', 'fat'] as const).map((k) => (
            <label key={k} className="meals-field">{k}
              <input className="meals-input" type="number" min={0} value={draft.perServing[k]}
                onChange={(e) => set({ perServing: { ...draft.perServing, [k]: Number(e.target.value) }, macrosEstimated: false })} />
            </label>
          ))}
        </fieldset>
        <div className="meals-row">
          <label className="meals-toggle"><input type="checkbox" checked={draft.vegetarian} onChange={(e) => set({ vegetarian: e.target.checked })} /> Vegetarian</label>
          <label className="meals-toggle"><input type="checkbox" checked={draft.vegan} onChange={(e) => set({ vegan: e.target.checked })} /> Vegan</label>
          <label className="meals-toggle"><input type="checkbox" checked={draft.freezable === true} onChange={(e) => set({ freezable: e.target.checked })} /> Freezes</label>
        </div>

        <fieldset className="meals-fieldset">
          <legend>Ingredients (for the whole recipe)</legend>
          <ul className="meals-lines">
            {draft.ingredients.map((line, i) => (
              <li key={i} className="meals-line">
                <input className="meals-input" placeholder="As written, e.g. 500g lean mince" aria-label={`Ingredient ${i + 1}`}
                  value={line.text} onChange={(e) => setLine(i, { text: e.target.value })} />
                <input className="meals-input" placeholder="ingredient" aria-label={`Ingredient ${i + 1} name`} list="meals-editor-ingredients"
                  value={line.name} onChange={(e) => setLine(i, { name: e.target.value })} />
                <input className="meals-input meals-input--qty" type="number" min={0} placeholder="qty" aria-label={`Ingredient ${i + 1} quantity`}
                  value={line.quantity ?? ''} onChange={(e) => setLine(i, { quantity: e.target.value === '' ? null : Number(e.target.value) })} />
                <select className="meals-input meals-input--unit" aria-label={`Ingredient ${i + 1} unit`} value={line.unit ?? ''}
                  onChange={(e) => setLine(i, { unit: (e.target.value || null) as Unit | null })}>
                  <option value="">–</option><option value="g">g</option><option value="ml">ml</option><option value="each">each</option>
                </select>
                <button type="button" className="meals-icon-button" aria-label={`Remove ingredient ${i + 1}`}
                  onClick={() => set({ ingredients: draft.ingredients.filter((_, j) => j !== i) })}><Trash2 size={16} /></button>
              </li>
            ))}
          </ul>
          <datalist id="meals-editor-ingredients">{[...knownIngredients].map((n) => <option key={n} value={n} />)}</datalist>
          <button type="button" className="meals-button meals-button--small"
            onClick={() => set({ ingredients: [...draft.ingredients, { text: '', name: '', quantity: null, unit: null }] })}><Plus size={14} /> Line</button>
        </fieldset>

        {unknownNames.length > 0 && (
          <fieldset className="meals-fieldset">
            <legend>New ingredients</legend>
            <p className="meals-muted meals-small">These names are not in your kitchen's list yet. Fix a typo above, or say how each one is kept and shopped.</p>
            <ul className="meals-lines">
              {unknownNames.map((name) => (
                <li key={name} className="meals-line">
                  <strong className="meals-line__name">{name}</strong>
                  <select className="meals-input" aria-label={`${name} kept as`} value={fresh(name).tier}
                    onChange={(e) => setFresh(name, { tier: e.target.value as NewIngredient['tier'] })}>
                    <option value="tracked">tracked by amount</option><option value="staple">cupboard staple</option>
                  </select>
                  <select className="meals-input" aria-label={`${name} aisle`} value={fresh(name).aisle}
                    onChange={(e) => setFresh(name, { aisle: e.target.value })}>
                    {AISLES.map((a) => <option key={a} value={a}>{a}</option>)}
                  </select>
                </li>
              ))}
            </ul>
          </fieldset>
        )}

        <label className="meals-field">Method, one step per line
          <textarea className="meals-input" rows={8} value={draft.method.join('\n')} onChange={(e) => set({ method: e.target.value.split('\n') })} />
        </label>
        <label className="meals-field">Tips, one per line
          <textarea className="meals-input" rows={2} value={draft.tips.join('\n')} onChange={(e) => set({ tips: e.target.value.split('\n').filter(Boolean) })} />
        </label>
        <label className="meals-field">Swaps, one per line
          <textarea className="meals-input" rows={2} value={draft.swaps.join('\n')} onChange={(e) => set({ swaps: e.target.value.split('\n').filter(Boolean) })} />
        </label>
        <label className="meals-field">Photo {draft.photo || photo ? '(one is attached)' : ''}
          <input type="file" accept="image/*" onChange={async (e) => { const f = e.target.files?.[0]; if (f) setPhoto(await shrinkImage(f)) }} />
        </label>
      </div>

      {error && <p className="meals-error" role="alert">{error}</p>}
      <div className="meals-row">
        <button type="button" className="meals-button meals-button--primary" disabled={saving || !draft.name.trim()} onClick={save}>
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button type="button" className="meals-button" onClick={() => (recipe ? navigate(`/recipes/${recipe.id}`) : setDraft(null))}>Cancel</button>
      </div>
    </div>
  )
}
