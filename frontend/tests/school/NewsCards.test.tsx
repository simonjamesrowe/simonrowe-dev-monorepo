import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { NewsletterCard } from '../../src/school/components/NewsletterCard'
import { SchoolMessagesCard } from '../../src/school/components/SchoolMessagesCard'
import type { Newsletter, SchoolMessage } from '../../src/school/schoolNewsApi'

// 23:00 UTC on the 22nd is midnight on the 23rd in London: the date the newsletter carries.
const LATEST: Newsletter = {
  id: '171',
  title: 'Kilmorie Primary School Newsletter - 23rd October 2026',
  url: 'https://www.kilmorieschool.co.uk/parentportal/newsletter/?id=171',
  publishedAt: '2026-10-22T23:00:00Z',
  headline: 'End of a busy first half term',
  points: [
    { text: 'INSET day Monday 2 November.', yearGroups: [] },
    { text: 'Pupil Parliament elections the week of 2 November.', yearGroups: ['Year 2', 'Year 3', 'Year 4', 'Year 5', 'Year 6'] },
    { text: 'Year 6 are back from PGL.', yearGroups: ['Year 6'] },
    { text: 'Do not pass the Elsinore Road barriers.', yearGroups: [] },
  ],
}

const EARLIER: Newsletter = {
  id: '170',
  title: 'Kilmorie Primary School Newsletter - 16th October 2026',
  url: 'https://www.kilmorieschool.co.uk/parentportal/newsletter/?id=170',
  publishedAt: '2026-10-16T00:00:00Z',
  headline: 'Black History Month',
  points: [{ text: 'Workshops all week.', yearGroups: [] }],
}

describe('the newsletter card', () => {
  it('shows the latest newsletter with its date in the school’s time zone', () => {
    render(<NewsletterCard state={{ status: 'ready', items: [LATEST, EARLIER] }} onAsk={vi.fn()} />)

    expect(screen.getByText('Fri 23 Oct')).toBeInTheDocument()
    expect(screen.getByText('End of a busy first half term')).toBeInTheDocument()
    expect(screen.getByText('Years 2–6')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Full newsletter/ })).toHaveAttribute(
      'href',
      LATEST.url,
    )
  })

  it('switches to an earlier newsletter from the list of issues', () => {
    render(<NewsletterCard state={{ status: 'ready', items: [LATEST, EARLIER] }} onAsk={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: '16 Oct' }))

    expect(screen.getByText('Black History Month')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '16 Oct' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('asks the chat about the newsletter shown', () => {
    const onAsk = vi.fn()
    render(<NewsletterCard state={{ status: 'ready', items: [LATEST] }} onAsk={onAsk} />)

    fireEvent.click(screen.getByRole('button', { name: /Ask about it/ }))

    expect(onAsk).toHaveBeenCalledWith('What was in the school newsletter of Friday 23 October?')
  })

  it('links to the newsletter itself while its summary is still being written', () => {
    render(
      <NewsletterCard
        state={{ status: 'ready', items: [{ ...LATEST, headline: null, points: [] }] }}
        onAsk={vi.fn()}
      />,
    )

    expect(screen.getByText(/still being written/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Full newsletter/ })).toBeInTheDocument()
  })

  it('shows only the first few points beside a conversation', () => {
    render(
      <NewsletterCard state={{ status: 'ready', items: [LATEST, EARLIER] }} onAsk={vi.fn()} compact />,
    )

    expect(screen.queryByText('Do not pass the Elsinore Road barriers.')).toBeNull()
    expect(screen.queryByRole('group', { name: 'Newsletters' })).toBeNull()
  })

  it('says so when there is nothing, rather than showing an empty card', () => {
    render(<NewsletterCard state={{ status: 'ready', items: [] }} onAsk={vi.fn()} />)

    expect(screen.getByText('No newsletters yet this term.')).toBeInTheDocument()
  })
})

const MESSAGES: SchoolMessage[] = [
  {
    id: 'm1',
    title: 'Reception and Year 6 - Height and Weight',
    publishedAt: '2026-10-22T17:11:29Z',
    yearGroups: ['Reception', 'Year 6'],
  },
  { id: 'm2', title: 'Ellie Reeves Art Competition', publishedAt: '2026-10-23T15:07:00Z', yearGroups: [] },
]

describe('the From the school card', () => {
  it('lists each email with who it is for and when it came', () => {
    render(<SchoolMessagesCard state={{ status: 'ready', items: MESSAGES }} onAsk={vi.fn()} />)

    expect(screen.getByText('Reception, Year 6 · Thu 22 Oct')).toBeInTheDocument()
    expect(screen.getByText('All years · Fri 23 Oct')).toBeInTheDocument()
  })

  it('asks the chat about an email when it is clicked', () => {
    const onAsk = vi.fn()
    render(<SchoolMessagesCard state={{ status: 'ready', items: MESSAGES }} onAsk={onAsk} />)

    fireEvent.click(screen.getByText('Ellie Reeves Art Competition'))

    expect(onAsk).toHaveBeenCalledWith(
      'What did the school\'s email "Ellie Reeves Art Competition" on Friday 23 October say?',
    )
  })

  it('says when nothing new has come', () => {
    render(<SchoolMessagesCard state={{ status: 'ready', items: [] }} onAsk={vi.fn()} />)

    expect(screen.getByText('Nothing new from the school in the last month.')).toBeInTheDocument()
  })
})
