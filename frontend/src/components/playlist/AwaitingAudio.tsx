import { Loader2, Sparkles } from 'lucide-react'
import { useMemo, useState } from 'react'

import { formatAgo, formatDate, formatMinutes } from './playlistFormat'
import { readinessOf } from './readiness'
import { itemKey } from '../../hooks/playlistStore'
import { MAX_AUDIO_ITEMS, type PlaylistAudioResult, type PlaylistEntry } from '../../types/playlist'

interface AwaitingAudioProps {
  entries: PlaylistEntry[]
  autoVoice: boolean
  onMakeAudio: (entries: PlaylistEntry[]) => Promise<PlaylistAudioResult[]>
  onAutoVoice: (autoVoice: boolean) => void
  onRemove: (entry: PlaylistEntry) => void
}

/**
 * Summarised but not yet audio. Making audio spends the monthly text-to-speech budget, so it
 * happens only for what someone ticks, or for everything when automatic audio is switched on.
 */
export function AwaitingAudio({
  entries, autoVoice, onMakeAudio, onAutoVoice, onRemove,
}: AwaitingAudioProps) {
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [working, setWorking] = useState(false)
  const [outcome, setOutcome] = useState<string | null>(null)

  const rows = useMemo(() => entries.map((entry) => ({ entry, readiness: readinessOf(entry) })), [entries])
  const choosable = rows.filter((row) => row.readiness.kind === 'choosable')
  // A ticked item that has since started (or vanished) no longer counts.
  const chosen = choosable.filter((row) => selected.has(itemKey(row.entry)))
  const chosenSeconds = chosen.reduce(
    (sum, row) => sum + (row.readiness.kind === 'choosable' ? row.readiness.estimate : 0), 0)
  const atLimit = chosen.length >= MAX_AUDIO_ITEMS

  const toggle = (entry: PlaylistEntry) => {
    setSelected((previous) => {
      const next = new Set(previous)
      const key = itemKey(entry)
      if (next.has(key)) next.delete(key)
      else if (next.size < MAX_AUDIO_ITEMS) next.add(key)
      return next
    })
  }

  const selectAll = () => {
    setSelected(new Set(choosable.slice(0, MAX_AUDIO_ITEMS).map((row) => itemKey(row.entry))))
  }

  const makeAudio = async () => {
    setWorking(true)
    setOutcome(null)
    try {
      const results = await onMakeAudio(chosen.map((row) => row.entry))
      setSelected(new Set())
      if (results.some((result) => result.outcome === 'UNAVAILABLE')) {
        setOutcome('Audio is unavailable this month, so nothing more can be read aloud until it resets.')
      } else if (results.some((result) => result.outcome === 'INELIGIBLE')) {
        setOutcome("Some of these can't be read aloud.")
      }
    } finally {
      setWorking(false)
    }
  }

  return (
    <section aria-labelledby="playlist-awaiting-title" className="playlist-awaiting">
      <div className="playlist-section__head">
        <h2 className="playlist-section__title" id="playlist-awaiting-title">
          <Sparkles aria-hidden="true" size={18} />
          Summarised, not yet audio
          <span className="playlist-section__count">{entries.length}</span>
        </h2>
        {choosable.length > 1 && (
          <button className="playlist-button playlist-button--ghost" onClick={selectAll} type="button">
            {choosable.length > MAX_AUDIO_ITEMS ? `Select ${MAX_AUDIO_ITEMS}` : 'Select all'}
          </button>
        )}
      </div>
      <p className="playlist-awaiting__lede">
        Pick the ones you want to hear. Each is ready in about a minute and joins the end of the list.
      </p>

      <ul className="playlist-awaiting__list">
        {rows.map(({ entry, readiness }) => {
          const key = itemKey(entry)
          const checked = selected.has(key)
          const inputId = `awaiting-${key}`
          return (
            <li className={`awaiting-row awaiting-row--${readiness.kind}`} key={key}>
              {readiness.kind === 'choosable'
                ? (
                  <input
                    checked={checked}
                    className="awaiting-row__check"
                    disabled={!checked && atLimit}
                    id={inputId}
                    onChange={() => toggle(entry)}
                    type="checkbox"
                  />
                )
                : <span aria-hidden="true" className="awaiting-row__check-space" />}
              <label className="awaiting-row__text" htmlFor={readiness.kind === 'choosable' ? inputId : undefined}>
                <span className="awaiting-row__source">
                  {entry.sourceName}
                  <span className="awaiting-row__when">
                    {[formatDate(entry.publishedAt), `added ${formatAgo(entry.addedAt)}`]
                      .filter(Boolean).join(' · ')}
                  </span>
                </span>
                <span className="awaiting-row__title">{entry.title}</span>
              </label>
              <span className="awaiting-row__state">
                {readiness.kind === 'busy' && (
                  <Loader2 aria-hidden="true" className="awaiting-row__spinner" size={14} />
                )}
                {readiness.label}
              </span>
              {readiness.kind === 'blocked' && (
                <button
                  className="playlist-button playlist-button--ghost awaiting-row__remove"
                  onClick={() => onRemove(entry)}
                  type="button"
                >
                  Remove
                </button>
              )}
            </li>
          )
        })}
      </ul>

      {outcome && <p className="playlist-awaiting__outcome" role="status">{outcome}</p>}

      <div className="playlist-awaiting__foot">
        <label className="playlist-switch">
          <input
            checked={autoVoice}
            className="playlist-switch__input"
            onChange={(event) => onAutoVoice(event.target.checked)}
            role="switch"
            type="checkbox"
          />
          <span aria-hidden="true" className="playlist-switch__track" />
          Make audio whenever something is summarised
        </label>
        <button
          className="playlist-button playlist-button--ai"
          disabled={chosen.length === 0 || working}
          onClick={() => { void makeAudio() }}
          type="button"
        >
          {working
            ? <Loader2 aria-hidden="true" className="awaiting-row__spinner" size={16} />
            : <Sparkles aria-hidden="true" size={16} />}
          {chosen.length === 0
            ? 'Make audio'
            : `Make audio for ${chosen.length} · ~${formatMinutes(chosenSeconds)}`}
        </button>
      </div>
    </section>
  )
}
