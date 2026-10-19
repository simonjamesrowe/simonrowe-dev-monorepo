import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../src/services/booksApi', () => ({ fetchBooks: vi.fn() }))
vi.mock('../../src/analytics/analytics', () => ({ track: vi.fn() }))

import { track } from '../../src/analytics/analytics'
import { ReadingPage } from '../../src/pages/ReadingPage'
import { fetchBooks } from '../../src/services/booksApi'
import type { Book } from '../../src/types/book'

const BOOKS: Book[] = [
  {
    slug: 'continuous-delivery', title: 'Continuous Delivery', subtitle: 'Reliable Software Releases',
    authors: ['Jez Humble', 'David Farley'], publishedYear: 2010, category: 'Delivery and production',
    summary: 'The book that defined the deployment pipeline.',
    description: 'All about **pipelines**.\n\n- Build once',
    note: 'This site deploys itself.',
    coverImage: { url: '/uploads/cd/original.jpg', formats: { small: { url: '/uploads/cd/small.jpg' } } },
    purchaseUrl: 'https://www.amazon.co.uk/dp/B003YMNVC0',
    sourceLabel: 'Trisha Gee', sourceUrl: 'https://trishagee.com/talk', displayOrder: 0,
  },
  {
    slug: 'peopleware', title: 'Peopleware', authors: ['Tom DeMarco', 'Timothy Lister'],
    category: 'Leading teams', summary: 'Sociology, not technology.', displayOrder: 1,
  },
  {
    slug: 'accelerate', title: 'Accelerate', authors: ['Nicole Forsgren', 'Jez Humble', 'Gene Kim'],
    category: 'Delivery and production', summary: 'Four key metrics.', displayOrder: 2,
    purchaseUrl: 'https://shop.example/accelerate',
  },
]

function LocationProbe() {
  return <p data-testid="location">{useLocation().search}</p>
}

function renderAt(path = '/reading') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<><ReadingPage /><LocationProbe /></>} path="/reading" />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ReadingPage', () => {
  beforeEach(() => {
    vi.mocked(fetchBooks).mockReset().mockResolvedValue(BOOKS)
    vi.mocked(track).mockReset()
  })

  it('shelves books under their categories in the order the first of each appears', async () => {
    renderAt()
    const shelves = await screen.findAllByRole('region')
    expect(shelves.map(shelf => within(shelf).getByRole('heading', { level: 2 }).textContent))
      .toEqual(['Delivery and production', 'Leading teams'])
    expect(within(shelves[0]).getAllByRole('heading', { level: 3 }).map(heading => heading.textContent))
      .toEqual(['Continuous Delivery', 'Accelerate'])
    expect(screen.getByText('Nicole Forsgren, Jez Humble and Gene Kim')).toBeInTheDocument()
    expect(document.title).toContain('Reading list')
  })

  it('labels the buy link by the parsed host and opens it in a new tab', async () => {
    renderAt()
    const amazon = await screen.findByRole('link', { name: /Buy on Amazon/ })
    expect(amazon).toHaveAttribute('href', 'https://www.amazon.co.uk/dp/B003YMNVC0')
    expect(amazon).toHaveAttribute('target', '_blank')
    expect(amazon).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByRole('link', { name: /Buy the book/ })).toHaveAttribute('href', 'https://shop.example/accelerate')
  })

  it('opens the drawer from a card, puts it in the address, and closes it again', async () => {
    const user = userEvent.setup()
    renderAt()
    await user.click(await screen.findByRole('button', { name: 'Continuous Delivery' }))

    const drawer = screen.getByRole('dialog', { name: 'Continuous Delivery' })
    expect(within(drawer).getByText('Reliable Software Releases')).toBeInTheDocument()
    expect(within(drawer).getByText('Delivery and production · First published 2010')).toBeInTheDocument()
    expect(within(drawer).getByRole('heading', { name: 'Why it’s on my list' })).toBeInTheDocument()
    expect(within(drawer).getByText('This site deploys itself.')).toBeInTheDocument()
    expect(within(drawer).getByText('pipelines').tagName).toBe('STRONG')
    expect(within(drawer).getByRole('link', { name: 'Trisha Gee' })).toHaveAttribute('href', 'https://trishagee.com/talk')
    expect(screen.getByTestId('location')).toHaveTextContent('?book=continuous-delivery')
    expect(track).toHaveBeenCalledWith('Book Opened', { slug: 'continuous-delivery' })

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('location')).toBeEmptyDOMElement()
  })

  it('opens the linked book from ?book=, and nothing for an unknown slug', async () => {
    renderAt('/reading?book=peopleware')
    const drawer = await screen.findByRole('dialog', { name: 'Peopleware' })
    // No description: the drawer falls back to the summary, and shows no note or source.
    expect(within(drawer).getByText('Sociology, not technology.')).toBeInTheDocument()
    expect(within(drawer).queryByRole('heading', { name: /on my list/ })).not.toBeInTheDocument()
    expect(within(drawer).queryByText(/Found via/)).not.toBeInTheDocument()
  })

  it('ignores a slug that is not on the list', async () => {
    renderAt('/reading?book=not-a-book')
    await screen.findByRole('heading', { name: 'Peopleware' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('records a purchase click with where it came from', async () => {
    const user = userEvent.setup()
    renderAt()
    await user.click(await screen.findByRole('link', { name: /Buy on Amazon/ }))
    expect(track).toHaveBeenCalledWith('Book Purchase Clicked', { slug: 'continuous-delivery', from: 'card' })
  })

  it('says so when the list is empty or cannot be loaded', async () => {
    vi.mocked(fetchBooks).mockResolvedValueOnce([])
    const { unmount } = renderAt()
    expect(await screen.findByText('Nothing on the shelf yet.')).toBeInTheDocument()
    unmount()

    vi.mocked(fetchBooks).mockRejectedValueOnce(new Error('Unable to load the reading list.'))
    renderAt()
    expect(await screen.findByText('Unable to load the reading list')).toBeInTheDocument()
  })
})
