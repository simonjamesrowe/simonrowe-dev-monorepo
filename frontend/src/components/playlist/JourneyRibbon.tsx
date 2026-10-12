import { TrainFront } from 'lucide-react'
import { useEffect, useState } from 'react'

import { JOURNEY_CHOICES, type JourneyPlan } from './journey'
import { formatMinutes } from './playlistFormat'
import type { PlaylistEntry } from '../../types/playlist'

interface JourneyRibbonProps {
  entries: PlaylistEntry[]
  currentIndex: number
  positionSeconds: number
  plan: JourneyPlan
  rate: number
  journeyEndsAt: number | null
  onJourney: (minutes: number | null) => void
}

function clockTime(epochMillis: number): string {
  return new Date(epochMillis).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
}

/**
 * The queue as one bar, each item a block as long as its audio, with the playhead and, when a
 * journey is set, the point where playback will pause. It shows at a glance how much there is
 * to hear and how much of it fits the trip.
 *
 * The bar itself is decoration (`aria-hidden`); the sentence under it says the same thing in
 * words.
 */
export function JourneyRibbon({
  entries, currentIndex, positionSeconds, plan, rate, journeyEndsAt, onJourney,
}: JourneyRibbonProps) {
  const [chosen, setChosen] = useState<number | null>(null)
  // Keep "pauses in N min" honest while nothing is playing to re-render the page.
  const [, setTick] = useState(0)
  useEffect(() => {
    if (journeyEndsAt === null) return undefined
    const timer = window.setInterval(() => setTick((tick) => tick + 1), 30_000)
    return () => window.clearInterval(timer)
  }, [journeyEndsAt])

  const choose = (minutes: number | null) => {
    setChosen(minutes)
    onJourney(minutes)
  }

  const stopEntry = plan.stopIndex >= 0 ? entries[plan.stopIndex] : undefined
  const minutesLeft = journeyEndsAt === null
    ? 0
    : Math.max(0, Math.round((journeyEndsAt - Date.now()) / 60_000))
  let status = `Plays all ${entries.length} in order.`
  if (journeyEndsAt !== null) {
    status = stopEntry
      ? `Pauses at ${clockTime(journeyEndsAt)}, in ${minutesLeft} min, during “${stopEntry.title}”.`
      : `Pauses at ${clockTime(journeyEndsAt)}, after everything here has played.`
  }

  return (
    <section aria-label="Listening time" className="journey">
      <div className="journey__top">
        <p className="journey__total">
          <strong>{formatMinutes(plan.remainingSeconds / rate)}</strong>
          <span>
            {' '}left · {entries.length} up next{rate === 1 ? '' : ` · at ${rate}×`}
          </span>
        </p>
        <div aria-label="Pause after" className="journey__choices" role="group">
          <span className="journey__label">
            <TrainFront aria-hidden="true" size={16} /> Journey
          </span>
          <button
            aria-pressed={journeyEndsAt === null}
            className={`journey__chip${journeyEndsAt === null ? ' journey__chip--on' : ''}`}
            onClick={() => choose(null)}
            type="button"
          >
            All
          </button>
          {JOURNEY_CHOICES.map((minutes) => {
            const on = journeyEndsAt !== null && chosen === minutes
            return (
              <button
                aria-pressed={on}
                className={`journey__chip${on ? ' journey__chip--on' : ''}`}
                key={minutes}
                onClick={() => choose(minutes)}
                type="button"
              >
                {minutes} min
              </button>
            )
          })}
        </div>
      </div>

      <div aria-hidden="true" className="journey__ribbon">
        {entries.map((entry, index) => {
          const seconds = entry.audio.durationSeconds ?? 0
          const playing = index === currentIndex
          const beyond = plan.stopIndex >= 0 && index > plan.stopIndex
          return (
            <span
              className={[
                'journey__segment',
                playing ? 'journey__segment--playing' : '',
                beyond ? 'journey__segment--beyond' : '',
              ].filter(Boolean).join(' ')}
              key={`${entry.contentType}:${entry.contentId}`}
              style={{
                flexGrow: Math.max(seconds, 1),
                ...(playing && seconds > 0
                  ? { '--fill': `${Math.min(100, (positionSeconds / seconds) * 100)}%` }
                  : {}),
              } as React.CSSProperties}
              title={entry.title}
            />
          )
        })}
        {plan.playheadFraction !== null && (
          <span className="journey__head" style={{ left: `${plan.playheadFraction * 100}%` }} />
        )}
        {plan.stopFraction !== null && (
          <span className="journey__stop" style={{ left: `${plan.stopFraction * 100}%` }}>
            <span className="journey__stop-label">{chosen ? `${chosen} min` : 'Stop'}</span>
          </span>
        )}
      </div>

      <p aria-live="polite" className="journey__status">{status}</p>
    </section>
  )
}
