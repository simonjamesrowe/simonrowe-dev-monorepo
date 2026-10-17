import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { rating, recipe } from './fixtures'
import { Home } from './Home'
import { ImportPage } from './ImportPage'
import { Library } from './Library'
import { RecipePage } from './RecipePage'
import { fakeApi, withApi } from './testing'

const chilli = recipe({ photo: 'abc.jpg' })
const oats = recipe({
  id: 'test-oats', name: 'Test Oats', category: 'breakfast', vegetarian: true, freezable: false, servings: 1,
  mainProtein: null, ingredients: [{ text: '40g oats', name: 'oats', quantity: 40, unit: 'g' }],
})

beforeEach(() => {
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:photo') }))
  vi.stubGlobal('scrollTo', vi.fn())
})
afterEach(() => vi.unstubAllGlobals())

describe('cook screen', () => {
  it('points an empty library at the import page', () => {
    render(withApi(fakeApi(), <Home recipes={[]} ratings={[]} />))
    expect(screen.getByRole('link', { name: 'Import' })).toHaveAttribute('href', '/import')
  })

  it('switches meal and shows favourites and something new', async () => {
    render(withApi(fakeApi(), <Home recipes={[chilli, oats]} ratings={[rating({ at: '2026-01-01T00:00:00Z' })]}
      now={new Date('2026-10-17T19:00:00Z')} />))
    const favourites = screen.getByRole('heading', { name: 'Your favourites' }).parentElement!
    expect(within(favourites).getByText('Test Chilli')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('tab', { name: 'Breakfast' }))
    const untried = screen.getByRole('heading', { name: 'Something new' }).parentElement!
    expect(within(untried).getByText('Test Oats')).toBeInTheDocument()
  })
})

describe('library', () => {
  it('filters by category, vegetarian and search, and sorts', async () => {
    render(withApi(fakeApi(), <Library recipes={[chilli, oats]} ratings={[rating({ score: 4 })]} />))
    expect(screen.getByText('2 shown')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Vegetarian' }))
    expect(screen.getByText('1 shown')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Vegetarian' }))

    await userEvent.type(screen.getByLabelText('Search recipes'), 'mince')
    expect(screen.getByText('Test Chilli')).toBeInTheDocument()
    expect(screen.queryByText('Test Oats')).not.toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('Search recipes'))

    await userEvent.click(screen.getByRole('button', { name: 'Breakfast' }))
    expect(screen.getByText('1 shown')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'All' }))
    await userEvent.selectOptions(screen.getByLabelText('Sort'), 'protein')
    await userEvent.selectOptions(screen.getByLabelText('Book'), 'mmmb1')
    expect(screen.getByText('★ 4.0')).toBeInTheDocument()
  })

  it('fetches a photo with the token once, then serves it from the cache', async () => {
    const pictured = recipe({ id: 'pictured', name: 'Pictured', photo: 'only-here.jpg' })
    const api = fakeApi()
    const first = render(withApi(api, <Library recipes={[pictured]} ratings={[]} />))
    await waitFor(() => expect(first.container.querySelector('.meals-photo img')).toHaveAttribute('src', 'blob:photo'))
    first.unmount()

    const again = render(withApi(api, <Library recipes={[pictured]} ratings={[]} />))
    await waitFor(() => expect(again.container.querySelector('.meals-photo img')).toHaveAttribute('src', 'blob:photo'))
    expect(api.image).toHaveBeenCalledTimes(1)
    expect(api.image).toHaveBeenCalledWith('only-here.jpg')
  })
})

describe('recipe page', () => {
  it('walks through cook mode and records how it went', async () => {
    const api = fakeApi()
    render(withApi(api, <RecipePage recipe={chilli} ratings={[]} ingredients={new Map()} />))

    await userEvent.click(screen.getByRole('button', { name: /Cook mode/ }))
    const cook = screen.getByRole('dialog', { name: 'Cooking Test Chilli' })
    expect(within(cook).getByText('Get out')).toBeInTheDocument()
    await userEvent.click(within(cook).getByRole('button', { name: /Start/ }))
    expect(within(cook).getByText('Step 1 of 3')).toBeInTheDocument()
    await userEvent.click(within(cook).getByRole('button', { name: /Next/ }))
    await userEvent.click(within(cook).getByRole('button', { name: /Back/ }))
    await userEvent.click(within(cook).getByRole('button', { name: /Next/ }))
    await userEvent.click(within(cook).getByRole('button', { name: /Next/ }))
    await userEvent.click(within(cook).getByRole('button', { name: /Done/ }))

    const sheet = screen.getByRole('dialog', { name: 'Cooked Test Chilli' })
    expect(within(sheet).getByRole('button', { name: 'Save' })).toBeDisabled()
    await userEvent.click(within(sheet).getByRole('radio', { name: '4 stars' }))
    await userEvent.click(within(sheet).getByRole('button', { name: 'spicy' }))
    await userEvent.click(within(sheet).getByLabelText('Would make again'))
    await userEvent.type(within(sheet).getByLabelText('Notes'), ' more chilli ')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.rate).toHaveBeenCalledWith({
      recipeId: 'test-chilli', score: 4, again: false, tags: ['spicy'], notes: 'more chilli',
    }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('shows past ratings and deletes one', async () => {
    const api = fakeApi()
    render(withApi(api, <RecipePage recipe={chilli} ratings={[rating({ notes: 'needs salt', tags: ['filling'] })]}
      ingredients={new Map([['salt', { name: 'salt', unit: null, tier: 'staple', aisle: 'Cupboard', assumed: true }]])} />))

    expect(screen.getByText('needs salt')).toBeInTheDocument()
    expect(screen.getByText('★ 5.0 · cooked 1×')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Delete this rating' }))
    await waitFor(() => expect(api.deleteRating).toHaveBeenCalledWith('r1'))
  })

  it('closes the cooked sheet from its backdrop and with Escape', async () => {
    render(withApi(fakeApi(), <RecipePage recipe={chilli} ratings={[]} ingredients={new Map()} />))
    await userEvent.click(screen.getByRole('button', { name: /Cooked it/ }))
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('import page', () => {
  it('uploads each bundle in turn and reports what each did', async () => {
    const api = fakeApi({
      importBundle: vi.fn()
        .mockResolvedValueOnce({ recipesAdded: 50, recipesExisting: 0, ingredientsAdded: 113, templatesAdded: 33, photosStored: 50, skipped: ['recipe x: no name'] })
        .mockRejectedValueOnce(new Error('Not a readable zip file')),
    })
    render(withApi(api, <ImportPage />))

    await userEvent.upload(screen.getByLabelText(/Choose bundles/),
      [new File(['a'], 'one.zip', { type: 'application/zip' }), new File(['b'], 'two.zip', { type: 'application/zip' })])

    expect(await screen.findByText(/50 recipes added/)).toBeInTheDocument()
    expect(screen.getByText('1 skipped')).toBeInTheDocument()
    expect(await screen.findByText('Not a readable zip file')).toBeInTheDocument()
    expect(api.importBundle).toHaveBeenCalledTimes(2)
  })
})
