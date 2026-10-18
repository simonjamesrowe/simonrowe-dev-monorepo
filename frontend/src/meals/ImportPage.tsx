import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Upload } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useMealsApi } from './data'
import type { ImportReport } from './types'

interface Result {
  file: string
  report?: ImportReport
  error?: string
}

/**
 * Loading recipe bundles. The books are copyrighted, so they are never in the repository:
 * each one arrives as a zip uploaded here. Uploading a bundle twice adds nothing.
 */
export function ImportPage() {
  const api = useMealsApi()
  const client = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [results, setResults] = useState<Result[]>([])

  async function upload(files: FileList | null) {
    if (!files?.length) return
    setBusy(true)
    const done: Result[] = []
    for (const file of Array.from(files)) {
      try {
        done.push({ file: file.name, report: await api.importBundle(file) })
      } catch (e) {
        done.push({ file: file.name, error: e instanceof Error ? e.message : String(e) })
      }
      setResults([...done])
    }
    await client.invalidateQueries({ queryKey: ['meals'] })
    setBusy(false)
  }

  return (
    <section className="meals-panel">
      <h2 className="meals-panel__title">Load recipe bundles</h2>
      <p className="meals-muted meals-small">
        A one-off admin tool for loading recipe collections from a zip bundle. Uploading a bundle again adds
        nothing, and recipes already in the library are left as they are. To add a single recipe, use{' '}
        <Link className="meals-link" to="/recipes/new">Import a recipe</Link> instead.
      </p>
      <label className={`meals-dropzone ${busy ? 'meals-dropzone--busy' : ''}`}>
        <Upload size={24} aria-hidden="true" />
        <span>{busy ? 'Importing…' : 'Choose bundles'}</span>
        <input type="file" accept=".zip,application/zip" multiple disabled={busy}
          onChange={(e) => { void upload(e.target.files); e.target.value = '' }} />
      </label>
      {results.length > 0 && (
        <ul className="meals-reports">
          {results.map((r) => (
            <li key={r.file} className="meals-report">
              <strong>{r.file}</strong>
              {r.error && <p className="meals-error">{r.error}</p>}
              {r.report && (
                <>
                  <p>
                    {r.report.recipesAdded} recipes added, {r.report.recipesExisting} already there,{' '}
                    {r.report.ingredientsAdded} ingredients, {r.report.photosStored} photos.
                  </p>
                  {r.report.skipped.length > 0 && (
                    <details>
                      <summary>{r.report.skipped.length} skipped</summary>
                      <ul>{r.report.skipped.map((s) => <li key={s}>{s}</li>)}</ul>
                    </details>
                  )}
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
