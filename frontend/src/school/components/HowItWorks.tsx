import { ChevronDown, ChevronRight } from 'lucide-react'
import { useState } from 'react'

const SCHOOL_URL = 'https://www.kilmorieschool.co.uk'

/**
 * What Term Time reads, and what it does not.
 *
 * Collapsed by default: the page's job is to answer a question, and a wall of explanation above
 * the input gets in the way of that. But it is on the page rather than buried in a separate
 * document, because someone deciding whether to trust an unofficial school bot should not have
 * to go looking for how it works.
 */
export function HowItWorks() {
  const [open, setOpen] = useState(false)

  return (
    <section className="school-about">
      <button
        type="button"
        className="school-about__toggle"
        aria-expanded={open}
        onClick={() => setOpen((prior) => !prior)}
      >
        {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        How this works
      </button>

      {open && (
        <div className="school-about__body">
          <p>
            Term Time answers from what the school itself publishes, plus the general emails it
            sends to parents. It is built and run by a parent, not by the school.
          </p>

          <h3>What it reads</h3>
          <ul>
            <li>
              <strong>The school calendar.</strong> Term dates, INSET days and events, taken from
              the calendar on{' '}
              <a href={SCHOOL_URL} target="_blank" rel="noopener noreferrer">
                kilmorieschool.co.uk
              </a>
              , including the separate calendars for each year group.
            </li>
            <li>
              <strong>The school website.</strong> News, newsletters and information pages, and
              the PDFs linked from them — the enrichment timetable, term dates and lunch menus.
            </li>
            <li>
              <strong>General emails from the school</strong> to parents and carers, and their
              PDF attachments.
            </li>
          </ul>

          <h3>What it does not do</h3>
          <ul>
            <li>
              It never shows anything about an individual child. Content that names someone who
              is not published school staff is held back, and names are removed from answers.
            </li>
            <li>
              It does not read replies, personal correspondence, or anything from payment
              services.
            </li>
            <li>It cannot book anything, contact the school, or change anything.</li>
          </ul>

          <h3>How current it is</h3>
          <p>
            The calendar is re-read every few hours, emails every half hour, and the website
            daily. The school&rsquo;s own pages are sometimes a long way out of date — when two
            sources disagree, Term Time prefers the calendar, then a recent letter, then the
            website.
          </p>

          <p className="school-about__caveat">
            It can be wrong, and it will say so when it does not know rather than guess. For
            anything that matters, check{' '}
            <a href={SCHOOL_URL} target="_blank" rel="noopener noreferrer">
              the school&rsquo;s own website
            </a>{' '}
            or contact the office.
          </p>
        </div>
      )}
    </section>
  )
}
