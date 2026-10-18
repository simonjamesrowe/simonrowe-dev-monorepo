import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Camera, Check, ChevronDown, Loader2, Minus, Pencil, Plus, Sparkles, Trash2, X } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

import { useMealsApi } from './data'
import { shrinkImage } from './images'
import { formatQty, nearNames, parseLine } from './logic'
import { usePhoto } from './photos'
import { RecipeImport } from './RecipeImport'
import type { Category, IngredientLine, NewIngredient, Recipe, RecipeInput, Unit } from './types'

const CATEGORIES: { key: Category; label: string }[] = [
  { key: 'breakfast', label: 'Breakfast' }, { key: 'main', label: 'Main' }, { key: 'snack', label: 'Snack' },
  { key: 'dessert', label: 'Dessert' }, { key: 'drink', label: 'Drink' },
]
const AISLES = ['Fruit & veg', 'Meat & fish', 'Dairy & eggs', 'Bakery', 'Frozen', 'Cupboard', 'Other']
const PROTEINS = ['chicken', 'beef', 'pork', 'fish', 'egg', 'meat-free']
const UNITS: { key: Unit | null; label: string }[] = [{ key: null, label: '–' }, { key: 'g', label: 'g' }, { key: 'ml', label: 'ml' }, { key: 'each', label: 'each' }]
const MACROS = [{ key: 'kcal', unit: '' }, { key: 'protein', unit: 'g' }, { key: 'carbs', unit: 'g' }, { key: 'fat', unit: 'g' }] as const

const capitalise = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)
const emptyLine = (): IngredientLine => ({ text: '', name: '', quantity: null, unit: null })

function blankRecipe(): RecipeInput {
  return {
    name: '', category: 'main', section: null, servings: 1,
    perServing: { kcal: 0, protein: 0, carbs: 0, fat: 0 }, macrosEstimated: false,
    vegetarian: false, vegan: false, freezable: null, mainProtein: null,
    ingredients: [emptyLine()],
    method: [], tips: [], swaps: [], sourceType: 'TEXT', sourceUrl: null, photo: null, newIngredients: [],
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

function sourceNote(draft: RecipeInput): string | null {
  if (draft.sourceType === 'LINK' && draft.sourceUrl) {
    try {
      return `Read from ${new URL(draft.sourceUrl).hostname.replace(/^www\./, '')}`
    } catch {
      return null
    }
  }
  if (draft.sourceType === 'PHOTO') return 'Read from your photo'
  return null
}

/**
 * Adds a recipe (pasted, read from a link or a photo, or typed) or edits one. Whatever the model
 * read is only a draft: nothing is saved until the owner presses Save, and new ingredient names
 * are added to the catalogue only as reviewed here.
 *
 * The model has already matched every line to a name, amount and unit, so a line shows as it was
 * written with its match beside it, and opens for editing only when the match is wrong. A draft
 * with no photo gets one made while it is being checked.
 */
export function RecipeEditor({ recipe, knownIngredients }: { recipe?: Recipe; knownIngredients: Set<string> }) {
  const api = useMealsApi()
  const client = useQueryClient()
  const navigate = useNavigate()
  const [draft, setDraft] = useState<RecipeInput | null>(recipe ? toInput(recipe) : null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [upload, setUpload] = useState<Blob | null>(null)
  const [making, setMaking] = useState(false)
  const [photoError, setPhotoError] = useState<string | null>(null)
  const [editing, setEditing] = useState<Set<number>>(new Set())
  const [showNew, setShowNew] = useState(false)
  const [extras, setExtras] = useState(false)
  // The photo being made, so Save can wait for it, and a way to stop it if the owner leaves or
  // picks a photo of their own first.
  const pending = useRef<{ promise: Promise<string | null>; abort: AbortController } | null>(null)

  const stopMaking = useCallback(() => {
    pending.current?.abort.abort()
    pending.current = null
    setMaking(false)
  }, [])
  useEffect(() => stopMaking, [stopMaking])

  const makePhoto = useCallback((of: RecipeInput) => {
    stopMaking()
    const abort = new AbortController()
    setMaking(true)
    setPhotoError(null)
    const promise = api.generatePhoto(of.name, of.ingredients.map((l) => l.name).filter(Boolean), abort.signal)
      .then(({ name }) => {
        if (abort.signal.aborted) return null
        setDraft((d) => (d ? { ...d, photo: name } : d))
        setUpload(null)
        return name
      })
      .catch((e: unknown) => {
        if (!abort.signal.aborted) setPhotoError(e instanceof Error ? e.message : String(e))
        return null
      })
      .finally(() => {
        if (pending.current?.abort === abort) {
          pending.current = null
          setMaking(false)
        }
      })
    pending.current = { promise, abort }
  }, [api, stopMaking])

  function start(read: RecipeInput, photo: Blob | null) {
    setDraft(read)
    setUpload(photo)
    if (!photo && !read.photo && read.name.trim()) makePhoto(read)
  }

  async function save() {
    if (!draft) return
    setSaving(true)
    setError(null)
    try {
      let photoName = draft.photo
      if (pending.current) photoName = (await pending.current.promise) ?? photoName
      if (upload) photoName = (await api.uploadPhoto(upload)).name
      const lines = draft.ingredients
        .filter((l) => l.text.trim() || l.name.trim())
        .map((l) => ({ ...l, name: l.name.trim().toLowerCase(), text: l.text.trim() || l.name.trim() }))
      // Every name not in the catalogue goes in as reviewed, or as tracked/Other if untouched.
      const unknown = [...new Set(lines.map((l) => l.name).filter((n) => n && !knownIngredients.has(n)))]
      const input: RecipeInput = {
        ...draft,
        photo: photoName,
        ingredients: lines,
        method: draft.method.map((m) => m.trim()).filter(Boolean),
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
    return <RecipeImport onDraft={start} onBlank={() => { setDraft(blankRecipe()); setEditing(new Set([0])) }} />
  }

  const set = (patch: Partial<RecipeInput>) => setDraft({ ...draft, ...patch })
  const setLine = (i: number, patch: Partial<IngredientLine>) =>
    set({ ingredients: draft.ingredients.map((l, j) => (j === i ? { ...l, ...patch } : l)) })
  // Typing a line fills in its match, until the name, amount or unit has been set by hand.
  const typeLine = (i: number, text: string) => {
    const line = draft.ingredients[i]
    const guessed = parseLine(line.text)
    const untouched = line.name === guessed.name && line.quantity === guessed.quantity && line.unit === guessed.unit
    setLine(i, untouched || (!line.name && line.quantity == null && line.unit == null) ? { text, ...parseLine(text) } : { text })
  }
  const toggleEditing = (i: number) => setEditing((e) => {
    const next = new Set(e)
    if (next.has(i)) next.delete(i)
    else next.add(i)
    return next
  })
  const removeLine = (i: number) => {
    set({ ingredients: draft.ingredients.filter((_, j) => j !== i) })
    setEditing((e) => new Set([...e].filter((j) => j !== i).map((j) => (j > i ? j - 1 : j))))
  }
  const addLine = () => {
    set({ ingredients: [...draft.ingredients, emptyLine()] })
    setEditing((e) => new Set([...e, draft.ingredients.length]))
  }
  const unknownNames = [...new Set(draft.ingredients.map((l) => l.name.trim().toLowerCase()).filter((n) => n && !knownIngredients.has(n)))]
  const fresh = (name: string): NewIngredient =>
    draft.newIngredients.find((n) => n.name === name) ?? { name, unit: null, tier: 'tracked', aisle: 'Other' }
  const setFresh = (name: string, patch: Partial<NewIngredient>) =>
    set({ newIngredients: [...draft.newIngredients.filter((n) => n.name !== name), { ...fresh(name), ...patch }] })
  const note = sourceNote(draft)

  return (
    <div className="meals-page meals-editor">
      <header className="meals-page__header">
        <h1>{recipe ? `Edit ${recipe.name}` : 'Check and save'}</h1>
        {!recipe && <p className="meals-muted meals-small">{note ? `${note}. ` : ''}Fix anything it got wrong, then save.</p>}
      </header>

      <PhotoSlot stored={draft.photo} upload={upload} making={making} error={photoError} canMake={!!draft.name.trim()}
        onMake={() => makePhoto(draft)}
        onUpload={async (file) => { stopMaking(); setPhotoError(null); setUpload(await shrinkImage(file)) }}
        onRemove={() => { stopMaking(); setUpload(null); set({ photo: null }) }} />

      <div className="meals-form">
        <label className="meals-field">Name
          <input className="meals-input" value={draft.name} onChange={(e) => set({ name: e.target.value })} />
        </label>

        <Group label="Meal">
          {CATEGORIES.map((c) => (
            <Chip key={c.key} on={draft.category === c.key} onClick={() => set({ category: c.key })}>{c.label}</Chip>
          ))}
        </Group>

        <div className="meals-editor__pair">
          <div className="meals-field">
            <span id="meals-makes">Makes</span>
            <span className="meals-stepper" role="group" aria-labelledby="meals-makes">
              <button type="button" className="meals-icon-button" aria-label="Fewer portions" disabled={draft.servings <= 1}
                onClick={() => set({ servings: Math.max(1, draft.servings - 1) })}><Minus size={16} /></button>
              <span aria-live="polite">{draft.servings} {draft.servings === 1 ? 'portion' : 'portions'}</span>
              <button type="button" className="meals-icon-button" aria-label="More portions" disabled={draft.servings >= 50}
                onClick={() => set({ servings: Math.min(50, draft.servings + 1) })}><Plus size={16} /></button>
            </span>
          </div>
          <fieldset className="meals-filter-group meals-editor__macros">
            <legend>Per portion{draft.macrosEstimated && <span className="meals-tag meals-tag--new">estimated</span>}</legend>
            <div className="meals-editor__macro-row">
              {MACROS.map(({ key, unit }) => (
                <label key={key} className="meals-editor__macro">
                  <input className="meals-input" type="number" min={0} inputMode="decimal" aria-label={key}
                    value={draft.perServing[key]}
                    onChange={(e) => set({ perServing: { ...draft.perServing, [key]: Number(e.target.value) }, macrosEstimated: false })} />
                  <span>{unit ? `${unit} ${key}` : key}</span>
                </label>
              ))}
            </div>
          </fieldset>
        </div>

        <Group label="Main protein">
          <Chip on={draft.mainProtein == null} onClick={() => set({ mainProtein: null })}>None</Chip>
          {PROTEINS.map((p) => (
            <Chip key={p} on={draft.mainProtein === p} onClick={() => set({ mainProtein: p })}>{capitalise(p)}</Chip>
          ))}
        </Group>

        <Group label="Good to know">
          <Chip on={draft.vegetarian} onClick={() => set({ vegetarian: !draft.vegetarian, vegan: draft.vegetarian ? false : draft.vegan })}>Vegetarian</Chip>
          <Chip on={draft.vegan} onClick={() => set({ vegan: !draft.vegan, vegetarian: draft.vegan ? draft.vegetarian : true })}>Vegan</Chip>
          <Chip on={draft.freezable === true} onClick={() => set({ freezable: draft.freezable === true ? false : true })}>Freezes</Chip>
        </Group>

        <section className="meals-editor__section" aria-labelledby="meals-ingredients">
          <h2 id="meals-ingredients" className="meals-editor__heading">Ingredients <span className="meals-muted meals-small">for the whole recipe</span></h2>
          <ul className="meals-editor__lines">
            {draft.ingredients.map((line, i) => (
              <li key={i} className="meals-editor__line">
                {editing.has(i) ? (
                  <LineEditor index={i} line={line} known={knownIngredients}
                    onText={(text) => typeLine(i, text)} onChange={(patch) => setLine(i, patch)}
                    onDone={() => toggleEditing(i)} onRemove={() => removeLine(i)} />
                ) : (
                  <div className="meals-editor__line-view">
                    <span className="meals-editor__line-body">
                      <span className="meals-editor__line-text">{line.text || line.name}</span>
                      <span className="meals-editor__line-match">
                        {line.name || 'no ingredient'}
                        {line.quantity != null && line.unit && ` · ${formatQty(line.quantity, line.unit)}${line.unit === 'each' ? ' each' : ''}`}
                        {line.name && !knownIngredients.has(line.name.trim().toLowerCase()) && <span className="meals-tag meals-tag--new">new</span>}
                      </span>
                    </span>
                    <button type="button" className="meals-icon-button meals-icon-button--small" aria-label={`Edit ${line.text || line.name || `ingredient ${i + 1}`}`}
                      onClick={() => toggleEditing(i)}><Pencil size={15} /></button>
                    <button type="button" className="meals-icon-button meals-icon-button--small" aria-label={`Remove ${line.text || line.name || `ingredient ${i + 1}`}`}
                      onClick={() => removeLine(i)}><Trash2 size={15} /></button>
                  </div>
                )}
              </li>
            ))}
          </ul>
          <button type="button" className="meals-button meals-button--small meals-editor__add" onClick={addLine}><Plus size={14} /> Add an ingredient</button>
        </section>

        {unknownNames.length > 0 && (
          <section className="meals-panel meals-panel--quiet meals-editor__new" aria-label="New ingredients">
            <button type="button" className="meals-editor__new-summary" aria-expanded={showNew} onClick={() => setShowNew(!showNew)}>
              <span>
                <strong>{unknownNames.length} new {unknownNames.length === 1 ? 'ingredient' : 'ingredients'}</strong> will be added to your kitchen
                <span className="meals-editor__new-names">{unknownNames.map((n) => `${n} (${fresh(n).tier === 'staple' ? 'cupboard' : fresh(n).aisle})`).join(', ')}</span>
              </span>
              <ChevronDown size={18} className={showNew ? 'meals-rotate' : ''} aria-hidden="true" />
            </button>
            {showNew && (
              <ul className="meals-editor__new-list">
                {unknownNames.map((name) => (
                  <li key={name} className="meals-editor__new-item">
                    <strong>{name}</strong>
                    <div className="meals-segmented meals-segmented--small" role="group" aria-label={`${name} kept as`}>
                      <button type="button" aria-pressed={fresh(name).tier === 'tracked'} className={fresh(name).tier === 'tracked' ? 'meals-segmented__on' : ''}
                        onClick={() => setFresh(name, { tier: 'tracked' })}>Fresh</button>
                      <button type="button" aria-pressed={fresh(name).tier === 'staple'} className={fresh(name).tier === 'staple' ? 'meals-segmented__on' : ''}
                        onClick={() => setFresh(name, { tier: 'staple' })}>Cupboard staple</button>
                    </div>
                    <div className="meals-chips" role="group" aria-label={`${name} aisle`}>
                      {AISLES.map((a) => (
                        <Chip key={a} on={fresh(name).aisle === a} onClick={() => setFresh(name, { aisle: a })}>{a}</Chip>
                      ))}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}

        <label className="meals-field">Method, one step per line
          <textarea className="meals-input" rows={Math.min(14, Math.max(5, draft.method.length * 2 + 1))} value={draft.method.join('\n')}
            onChange={(e) => set({ method: e.target.value.split('\n') })} />
        </label>
        {extras || draft.tips.length > 0 || draft.swaps.length > 0 ? (
          <>
            <label className="meals-field">Tips, one per line
              <textarea className="meals-input" rows={2} value={draft.tips.join('\n')} onChange={(e) => set({ tips: e.target.value.split('\n') })} />
            </label>
            <label className="meals-field">Swaps, one per line
              <textarea className="meals-input" rows={2} value={draft.swaps.join('\n')} onChange={(e) => set({ swaps: e.target.value.split('\n') })} />
            </label>
          </>
        ) : (
          <button type="button" className="meals-link meals-editor__extras" onClick={() => setExtras(true)}>+ Add tips or swaps</button>
        )}
      </div>

      <div className="meals-editor__bar">
        {error && <p className="meals-error" role="alert">{error}</p>}
        <div className="meals-row">
          <button type="button" className="meals-button meals-button--primary" disabled={saving || !draft.name.trim()} onClick={save}>
            {saving ? <><Loader2 className="meals-spin" size={16} /> {making ? 'Finishing the photo…' : 'Saving…'}</> : 'Save'}
          </button>
          <button type="button" className="meals-button" onClick={() => {
            stopMaking()
            if (recipe) navigate(`/recipes/${recipe.id}`)
            else { setDraft(null); setUpload(null); setError(null); setPhotoError(null); setEditing(new Set()) }
          }}>Cancel</button>
        </div>
      </div>
    </div>
  )
}

function Group({ label, children }: { label: string; children: ReactNode }) {
  return (
    <fieldset className="meals-filter-group">
      <legend>{label}</legend>
      <div className="meals-chips">{children}</div>
    </fieldset>
  )
}

function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" aria-pressed={on} className={`meals-chip ${on ? 'meals-chip--on' : ''}`} onClick={onClick}>{children}</button>
  )
}

function LineEditor({ index, line, known, onText, onChange, onDone, onRemove }: {
  index: number; line: IngredientLine; known: Set<string>
  onText: (text: string) => void; onChange: (patch: Partial<IngredientLine>) => void; onDone: () => void; onRemove: () => void
}) {
  const near = nearNames(line.name, known)
  const label = `Ingredient ${index + 1}`
  return (
    <div className="meals-editor__line-edit">
      <input className="meals-input" placeholder="As written, e.g. 500g lean mince" aria-label={label} autoFocus={!line.text}
        value={line.text} onChange={(e) => onText(e.target.value)} />
      <div className="meals-editor__line-fields">
        <input className="meals-input meals-editor__line-name" placeholder="ingredient" aria-label={`${label} name`}
          value={line.name} onChange={(e) => onChange({ name: e.target.value })} />
        <input className="meals-input meals-input--qty" type="number" min={0} inputMode="decimal" placeholder="qty" aria-label={`${label} quantity`}
          value={line.quantity ?? ''} onChange={(e) => onChange({ quantity: e.target.value === '' ? null : Number(e.target.value) })} />
        <div className="meals-segmented meals-segmented--small" role="group" aria-label={`${label} unit`}>
          {UNITS.map((u) => (
            <button key={u.label} type="button" aria-pressed={line.unit === u.key} className={line.unit === u.key ? 'meals-segmented__on' : ''}
              onClick={() => onChange({ unit: u.key })}>{u.label}</button>
          ))}
        </div>
      </div>
      {near.length > 0 && (
        <div className="meals-chips meals-editor__near" role="group" aria-label={`Known names like ${line.name}`}>
          <span className="meals-muted meals-small">Already in your kitchen:</span>
          {near.map((n) => <button key={n} type="button" className="meals-chip" onClick={() => onChange({ name: n })}>{n}</button>)}
        </div>
      )}
      <div className="meals-row">
        <button type="button" className="meals-button meals-button--small" onClick={onDone}><Check size={14} /> Done</button>
        <button type="button" className="meals-button meals-button--small" aria-label={`Remove ${label}`} onClick={onRemove}><Trash2 size={14} /> Remove</button>
      </div>
    </div>
  )
}

/** The recipe's photo: the one stored, the one about to be uploaded, or the one being made. */
function PhotoSlot({ stored, upload, making, error, canMake, onMake, onUpload, onRemove }: {
  stored: string | null; upload: Blob | null; making: boolean; error: string | null; canMake: boolean
  onMake: () => void; onUpload: (file: File) => void; onRemove: () => void
}) {
  const { ref, url: storedUrl } = usePhoto(upload ? null : stored)
  const [uploadUrl, setUploadUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!upload) {
      setUploadUrl(null)
      return
    }
    const url = URL.createObjectURL(upload)
    setUploadUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [upload])
  const shown = uploadUrl ?? (stored ? storedUrl : null)
  const has = !!upload || !!stored

  return (
    <section className="meals-editor__photo" aria-label="Photo">
      <span ref={ref} className="meals-photo meals-editor__photo-frame">
        {shown && !making && <img src={shown} alt="" />}
        {making && (
          <span className="meals-editor__photo-making" role="status">
            <Loader2 className="meals-spin" size={22} /> Making a photo…
          </span>
        )}
        {!shown && !making && (
          <span className="meals-editor__photo-making">
            <Camera size={22} aria-hidden="true" /> {has ? 'Loading…' : 'No photo yet'}
          </span>
        )}
      </span>
      <div className="meals-row meals-editor__photo-actions">
        <button type="button" className="meals-button meals-button--small meals-button--ai" disabled={making || !canMake} onClick={onMake}>
          <Sparkles size={14} /> {has ? 'Make another' : 'Make a photo'}
        </button>
        <label className="meals-button meals-button--small">
          <Camera size={14} aria-hidden="true" /> {has ? 'Use my own' : 'Add my own'}
          <input type="file" accept="image/*" className="meals-visually-hidden" aria-label="Choose a photo"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) onUpload(f); e.target.value = '' }} />
        </label>
        {has && !making && (
          <button type="button" className="meals-button meals-button--small" onClick={onRemove}><X size={14} /> Remove</button>
        )}
      </div>
      {error && <p className="meals-error meals-small" role="alert">{error}</p>}
    </section>
  )
}
