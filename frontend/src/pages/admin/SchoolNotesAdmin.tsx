import { useCallback, useEffect, useRef, useState } from 'react'
import { CalendarDays, ClipboardPaste, ExternalLink, Link2, Loader2 } from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import {
  createSchoolNote,
  fetchSchoolNote,
  fetchSchoolNotes,
  type SchoolNote,
} from '../../services/adminApi'

const YEAR_GROUPS = [
  'Reception',
  'Year 1',
  'Year 2',
  'Year 3',
  'Year 4',
  'Year 5',
  'Year 6',
]

/**
 * How long the page keeps asking whether the links have been fetched.
 *
 * Bounded rather than open-ended because `fetching` is held in memory on the server: a restart
 * mid-fetch leaves the links PENDING with nothing running, and a poll loop keyed on link status
 * alone would spin for ever on a note whose fetch died with the process. When this runs out the
 * links are still listed, still clickable and still fetchable by hand from the Documents screen.
 */
const POLL_INTERVAL_MS = 3000
const MAX_POLLS = 60

const PLACEHOLDER = `Paste the messages here, exactly as they arrived. For example:

Trinity C of E school - open morning Sat 19 Sept: https://www.trinity.lewisham.sch.uk/Secondary
Harris Boys - East Dulwich - 17 Sept - https://www.harrisdulwichboys.org.uk/admissions/open-events

Dates, times and links are read out of the text — there is nothing to fill in.`

function when(value: string): string {
  return new Date(value).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })
}

/**
 * Paste a note into Term Time.
 *
 * The case this exists for is a parents' WhatsApp group posting secondary-school open evenings:
 * a school, a date, a link, one per message, none of which will ever reach Term Time through the
 * mailbox, the calendar feed or the school's own website, because none of it is Kilmorie's.
 *
 * There is deliberately no date picker and no per-event form. Retyping four messages into a
 * structured form is slower than reading them, so the input is the text and a model does the
 * reading — and the result panel below shows what it understood, because an extraction nobody
 * checks is an extraction nobody should trust.
 */
export function SchoolNotesAdmin() {
  const { getAccessToken } = useAuth()
  const [text, setText] = useState('')
  const [title, setTitle] = useState('')
  const [yearGroups, setYearGroups] = useState<string[]>(['Year 6'])
  const [saving, setSaving] = useState(false)
  const [note, setNote] = useState<SchoolNote | null>(null)
  const [recent, setRecent] = useState<SchoolNote[]>([])
  const [error, setError] = useState<string | null>(null)
  const pollsLeft = useRef(MAX_POLLS)

  const loadRecent = useCallback(async () => {
    try {
      setRecent(await fetchSchoolNotes(getAccessToken))
    } catch {
      // The list of past notes is context, not the thing anyone came here to do. Failing to
      // load it must not blank the form above it.
      setRecent([])
    }
  }, [getAccessToken])

  useEffect(() => {
    void loadRecent()
  }, [loadRecent])

  // Polls while the server is still fetching the note's links, then stops for good. The
  // interval is cleared on unmount and whenever the note changes, so navigating away mid-fetch
  // leaves no timer behind.
  useEffect(() => {
    if (!note?.fetching || pollsLeft.current <= 0) {
      return
    }
    const id = window.setInterval(() => {
      pollsLeft.current -= 1
      if (pollsLeft.current <= 0) {
        window.clearInterval(id)
        return
      }
      fetchSchoolNote(getAccessToken, note.id)
        .then(setNote)
        .catch(() => window.clearInterval(id))
    }, POLL_INTERVAL_MS)
    return () => window.clearInterval(id)
  }, [getAccessToken, note])

  const save = async () => {
    setSaving(true)
    setError(null)
    try {
      pollsLeft.current = MAX_POLLS
      const saved = await createSchoolNote(getAccessToken, { text, title, yearGroups })
      setNote(saved)
      setText('')
      setTitle('')
      await loadRecent()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save that note')
    } finally {
      setSaving(false)
    }
  }

  const toggleYearGroup = (year: string) => {
    setYearGroups((current) =>
      current.includes(year) ? current.filter((y) => y !== year) : [...current, year],
    )
  }

  return (
    <div className="admin-page">
      <h1 className="admin-page__title">Paste a note</h1>
      <p className="school-admin__intro">
        Anything Term Time should know that will not arrive by email, calendar or the school
        website — secondary-school open evenings from the parents&rsquo; group chat, most often.
        Dates are read out of the text and every link in it is fetched, so the school&rsquo;s own
        page supplies the time and the booking address. Notes are public straight away: pasting
        one is the decision.
      </p>

      {error && <div className="admin-error-banner">{error}</div>}

      <form
        className="school-notes__form"
        onSubmit={(e) => {
          e.preventDefault()
          void save()
        }}
      >
        <label className="admin-form__label" htmlFor="note-text">
          The messages
        </label>
        <textarea
          id="note-text"
          className="admin-form__input school-notes__text"
          rows={12}
          value={text}
          placeholder={PLACEHOLDER}
          onChange={(e) => setText(e.target.value)}
        />

        <div className="school-notes__meta">
          <div>
            <label className="admin-form__label" htmlFor="note-title">
              Title (optional)
            </label>
            <input
              id="note-title"
              className="admin-form__input"
              value={title}
              placeholder="Taken from the first line if you leave this blank"
              onChange={(e) => setTitle(e.target.value)}
            />
          </div>
          <fieldset className="school-notes__years">
            <legend className="admin-form__label">Who this is for</legend>
            {YEAR_GROUPS.map((year) => (
              <label key={year} className="school-notes__year">
                <input
                  type="checkbox"
                  checked={yearGroups.includes(year)}
                  onChange={() => toggleYearGroup(year)}
                />
                {year}
              </label>
            ))}
            <p className="school-admin__source-note">
              Events that do not name a year group of their own are narrowed to these. Leave all
              of them unticked for whole-school.
            </p>
          </fieldset>
        </div>

        <button type="submit" className="admin-btn" disabled={saving || !text.trim()}>
          {saving ? (
            <>
              <Loader2 size={15} className="school-admin__spin" /> Reading the dates…
            </>
          ) : (
            <>
              <ClipboardPaste size={15} /> Save note
            </>
          )}
        </button>
      </form>

      {note && <NoteResult note={note} heading="Saved" />}

      {recent.length > 0 && (
        <section className="school-notes__recent">
          <h2>Recent notes</h2>
          {recent
            .filter((r) => r.id !== note?.id)
            .map((r) => (
              <NoteResult key={r.id} note={r} heading={when(r.publishedAt)} collapsed />
            ))}
        </section>
      )}
    </div>
  )
}

/** One note and what came of it: the dates found, and the pages the links led to. */
function NoteResult({
  note,
  heading,
  collapsed = false,
}: {
  note: SchoolNote
  heading: string
  collapsed?: boolean
}) {
  return (
    <section className="school-notes__result">
      <header className="school-notes__result-head">
        <h2>{note.title}</h2>
        <span className="school-admin__source-note">{heading}</span>
      </header>

      {!collapsed && <pre className="school-notes__body">{note.body}</pre>}

      <h3 className="school-admin__links-title">
        <CalendarDays size={14} />
        {note.events.length === 0
          ? 'No dates found in this note yet'
          : `${note.events.length} date${note.events.length === 1 ? '' : 's'} found`}
      </h3>
      {note.events.length > 0 && (
        <ul className="school-notes__events">
          {note.events.map((event) => (
            <li key={event.id}>
              <strong>{event.startDate}</strong> {event.title}
              {event.time && <span className="school-admin__link-url"> · {event.time}</span>}
              {event.location && (
                <span className="school-admin__link-url"> · {event.location}</span>
              )}
              <span className="school-admin__tag">
                {event.yearGroups.length ? event.yearGroups.join(', ') : 'Whole school'}
              </span>
              {event.sourceUrl && (
                <a href={event.sourceUrl} target="_blank" rel="noreferrer noopener">
                  <ExternalLink size={12} /> link
                </a>
              )}
            </li>
          ))}
        </ul>
      )}

      <h3 className="school-admin__links-title">
        <Link2 size={14} />
        {note.fetching ? (
          <>
            <Loader2 size={13} className="school-admin__spin" /> Reading the linked pages…
          </>
        ) : (
          `${note.links.length} link${note.links.length === 1 ? '' : 's'}`
        )}
      </h3>
      {note.links.length > 0 && (
        <ul className="school-notes__links">
          {note.links.map((link) => (
            <li key={link.id} className="school-admin__link">
              <span className="school-admin__link-detail">
                {/* A real anchor, unlike the email link queue: these addresses were pasted in
                    by the person reading this page, not sent to the school by a stranger. */}
                <a
                  className="school-admin__link-url"
                  href={link.url}
                  target="_blank"
                  rel="noreferrer noopener"
                >
                  {link.url}
                </a>
                <span className="school-admin__tag">{link.status}</span>
                {link.failureReason && (
                  <span className="school-admin__link-error">{link.failureReason}</span>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
