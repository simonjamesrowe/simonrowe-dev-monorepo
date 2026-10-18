import { useState, type ClipboardEvent } from 'react'
import { Camera, Loader2, Sparkles } from 'lucide-react'

import { useMealsApi } from './data'
import { shrinkImage } from './images'
import { recipeLink } from './logic'
import type { RecipeInput } from './types'

interface Props {
  /** A draft the model read, and the photo it was read from, if any. */
  onDraft: (draft: RecipeInput, photo: Blob | null) => void
  onBlank: () => void
}

/**
 * The first step of adding a recipe: one box that takes whatever was copied. A bare link is
 * read as a page; anything else (a caption, a message, a page copied by hand) is read as the
 * recipe itself. A photo can be chosen, or pasted straight into the box.
 */
export function RecipeImport({ onDraft, onBlank }: Props) {
  const api = useMealsApi()
  const [text, setText] = useState('')
  const [reading, setReading] = useState<'text' | 'link' | 'photo' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const link = recipeLink(text)

  async function read(how: 'text' | 'link' | 'photo', file?: Blob) {
    setReading(how)
    setError(null)
    try {
      if (how === 'photo' && file) {
        const small = await shrinkImage(file)
        onDraft(await api.readRecipePhoto(small), small)
      } else if (how === 'link' && link) {
        onDraft(await api.readRecipeLink(link), null)
      } else {
        onDraft(await api.readRecipeText(text), null)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setReading(null)
    }
  }

  function pasted(e: ClipboardEvent<HTMLTextAreaElement>) {
    const image = Array.from(e.clipboardData.files).find((f) => f.type.startsWith('image/'))
    if (image) {
      e.preventDefault()
      void read('photo', image)
    }
  }

  const busy = reading !== null
  return (
    <div className="meals-page">
      <header className="meals-page__header">
        <h1>Add a recipe</h1>
        <p className="meals-muted">
          Paste a recipe or a link to one: an Instagram caption, a message or a recipe page all work. It picks out
          the ingredients, method and macros, and makes a photo if there isn't one. Nothing is saved until you have
          checked it.
        </p>
      </header>

      <section className="meals-panel meals-panel--ai meals-import">
        <textarea className="meals-input meals-import__box" rows={9} aria-label="Recipe or link" disabled={busy}
          placeholder={'Paste the recipe here, or a link to it\n\nLEMON PROTEIN PANCAKES\n-40g rolled oats\n-150g cottage cheese\n…'}
          value={text} onChange={(e) => setText(e.target.value)} onPaste={pasted} />
        <div className="meals-import__actions">
          <button type="button" className="meals-button meals-button--primary" disabled={!text.trim() || busy}
            onClick={() => read(link ? 'link' : 'text')}>
            {reading === 'text' || reading === 'link' ? <Loader2 className="meals-spin" size={16} /> : <Sparkles size={16} />}
            {reading === 'link' ? 'Reading the page…' : reading === 'text' ? 'Reading…' : link ? 'Read the page' : 'Read it'}
          </button>
          <label className={`meals-button ${busy ? 'meals-button--disabled' : ''}`}>
            {reading === 'photo' ? <Loader2 className="meals-spin" size={16} /> : <Camera size={16} aria-hidden="true" />}
            {reading === 'photo' ? 'Reading the photo…' : 'From a photo'}
            <input type="file" accept="image/*" disabled={busy} className="meals-visually-hidden"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) void read('photo', f); e.target.value = '' }} />
          </label>
        </div>
        {busy && <p className="meals-muted meals-small" role="status">This takes a few seconds, sometimes up to a minute.</p>}
        {error && <p className="meals-error" role="alert">{error}</p>}
      </section>

      <p className="meals-muted meals-small">
        Nothing to paste? <button type="button" className="meals-link" disabled={busy} onClick={onBlank}>Type it in yourself</button>
      </p>
    </div>
  )
}
