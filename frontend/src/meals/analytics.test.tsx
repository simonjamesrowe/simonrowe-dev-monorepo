import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { rating, recipe } from './fixtures'
import { Kitchen } from './Kitchen'
import { More } from './More'
import { RecipeEditor } from './RecipeEditor'
import { RecipePage } from './RecipePage'
import { Shop } from './Shop'
import { fakeApi, withApi } from './testing'
import { UpNext } from './UpNext'

const { track, configured, openCookieSettings } = vi.hoisted(() => ({
  track: vi.fn(),
  configured: vi.fn(() => false),
  openCookieSettings: vi.fn(),
}))
vi.mock('../analytics/analytics', () => ({
  track,
  isAnalyticsConfigured: () => configured(),
  openCookieSettings: () => openCookieSettings(),
}))

const chilli = recipe()
const byId = new Map([[chilli.id, chilli]])

const pancakes = {
  name: 'Test Pancakes', category: 'breakfast', section: null, servings: 1,
  perServing: { kcal: 465, protein: 46, carbs: 44, fat: 11 }, macrosEstimated: false,
  vegetarian: true, vegan: false, freezable: null, mainProtein: 'egg',
  ingredients: [{ text: '40g rolled oats', name: 'oats', quantity: 40, unit: 'g' }],
  method: ['Blend.'], tips: [], swaps: [], sourceType: 'TEXT', sourceUrl: null, photo: 'read.jpg',
  newIngredients: [],
} as const

function events(name: string) {
  return track.mock.calls.filter(([event]) => event === name)
}

beforeEach(() => {
  track.mockClear()
  configured.mockReturnValue(false)
  openCookieSettings.mockClear()
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:photo') }))
  vi.stubGlobal('scrollTo', vi.fn())
})
afterEach(() => vi.unstubAllGlobals())

describe('Meals analytics: importing recipes', () => {
  it('reports a pasted recipe as imported via text, once it is saved', async () => {
    const api = fakeApi({
      readRecipeText: vi.fn().mockResolvedValue(pancakes),
      createRecipe: vi.fn().mockResolvedValue({ id: 'test-pancakes' }),
    })
    render(withApi(api, <RecipeEditor knownIngredients={new Set(['oats'])} />))

    await userEvent.type(screen.getByLabelText('Recipe or link'), 'Pancakes, oats')
    await userEvent.click(screen.getByRole('button', { name: 'Read it' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Save' }))
    // Reading a draft is not an import: nothing has been saved yet.
    await waitFor(() => expect(api.createRecipe).toHaveBeenCalled())

    await waitFor(() => expect(events('Recipe Imported')).toEqual([['Recipe Imported', { via: 'text' }]]))
  })

  it('reports a recipe read from a link as imported via link', async () => {
    const api = fakeApi({
      readRecipeLink: vi.fn().mockResolvedValue({ ...pancakes, sourceType: 'LINK', sourceUrl: 'https://www.example.com/o' }),
      createRecipe: vi.fn().mockResolvedValue({ id: 'test-pancakes' }),
    })
    render(withApi(api, <RecipeEditor knownIngredients={new Set(['oats'])} />))

    await userEvent.type(screen.getByLabelText('Recipe or link'), 'example.com/o')
    await userEvent.click(screen.getByRole('button', { name: 'Read the page' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Save' }))

    await waitFor(() => expect(events('Recipe Imported')).toEqual([['Recipe Imported', { via: 'link' }]]))
  })

  it('does not report a recipe typed in from blank, or an edit, as an import', async () => {
    const api = fakeApi({
      createRecipe: vi.fn().mockResolvedValue({ id: 'typed' }),
      updateRecipe: vi.fn().mockResolvedValue({ id: 'test-chilli' }),
      generatePhoto: vi.fn().mockResolvedValue({ name: 'made.jpg' }),
    })
    const blank = render(withApi(api, <RecipeEditor knownIngredients={new Set()} />))
    await userEvent.click(screen.getByRole('button', { name: 'Type it in yourself' }))
    await userEvent.type(screen.getByLabelText('Name'), 'Toast')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.createRecipe).toHaveBeenCalled())
    blank.unmount()

    render(withApi(api, <RecipeEditor recipe={chilli} knownIngredients={new Set(['beef mince', 'onion', 'salt'])} />))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.updateRecipe).toHaveBeenCalled())

    expect(events('Recipe Imported')).toEqual([])
  })

  it('reports each bundle that loaded, and not one that failed', async () => {
    const api = fakeApi({
      importBundle: vi.fn()
        .mockResolvedValueOnce({ recipesAdded: 5, recipesExisting: 0, ingredientsAdded: 1, templatesAdded: 0, photosStored: 5, skipped: [] })
        .mockRejectedValueOnce(new Error('Not a readable zip file')),
    })
    render(withApi(api, <More onSignOut={vi.fn()} />))

    await userEvent.upload(screen.getByLabelText(/Choose bundles/),
      [new File(['a'], 'one.zip', { type: 'application/zip' }), new File(['b'], 'two.zip', { type: 'application/zip' })])
    expect(await screen.findByText('Not a readable zip file')).toBeInTheDocument()

    expect(events('Recipe Imported')).toEqual([['Recipe Imported', { via: 'bundle' }]])
  })
})

describe('Meals analytics: the kitchen and the recipe page', () => {
  it('reports a Quick add with how many changes were kept, never the note', async () => {
    const api = fakeApi({
      readKitchen: vi.fn().mockResolvedValue({
        changes: [
          { name: 'egg', action: 'add', quantity: 12, unit: 'each', status: null, useBy: null, source: 'a dozen eggs' },
          { name: 'oats', action: 'status', quantity: null, unit: null, status: 'low', useBy: null, source: 'low on oats' },
        ],
        newIngredients: [],
      }),
    })
    render(withApi(api, <Kitchen ingredients={new Map()} />))

    await userEvent.type(screen.getByLabelText('What changed'), 'a dozen eggs, low on oats')
    await userEvent.click(screen.getByRole('button', { name: /Read it/ }))
    // The words each change was read from are the owner's own, so replay masks them.
    expect(await screen.findByText('“a dozen eggs”')).toHaveAttribute('data-amp-mask')
    await userEvent.click(screen.getByRole('checkbox', { name: /oats: low/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Update kitchen' }))

    await waitFor(() => expect(events('Quick Add Used')).toEqual([
      ['Quick Add Used', { itemCount: 1, withPhoto: false }],
    ]))
  })

  it('reports the recipe viewed by id, and a cook once it is saved', async () => {
    const api = fakeApi()
    render(withApi(api, <RecipePage recipe={chilli} ratings={[rating({ notes: 'needs salt' })]} ingredients={new Map()} />))

    expect(events('Recipe Viewed')).toEqual([['Recipe Viewed', { recipeId: 'test-chilli' }]])
    // A note the owner wrote is masked in replay.
    expect(screen.getByText('needs salt')).toHaveAttribute('data-amp-mask')

    await userEvent.click(screen.getByRole('button', { name: /Cooked it/ }))
    const sheet = screen.getByRole('dialog', { name: 'Cooked Test Chilli' })
    await userEvent.click(within(sheet).getByRole('radio', { name: '4 stars' }))
    await waitFor(() => expect(within(sheet).getByRole('button', { name: 'Save' })).toBeEnabled())
    expect(events('Recipe Cooked')).toEqual([])
    await userEvent.click(within(sheet).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(events('Recipe Cooked')).toEqual([['Recipe Cooked']]))
  })
})

describe('Meals analytics: Up next and shopping', () => {
  it('reports the shopping list once per visit, however often it refetches', async () => {
    const api = fakeApi({
      shopping: vi.fn().mockResolvedValue({
        upNextCount: 1,
        items: [
          { line: { name: 'beef mince', aisle: 'Meat & fish', tier: 'tracked', toBuy: 300, unit: 'g', why: '', recipes: [] }, searchTerm: 'beef mince 300g', basket: null },
          { line: { name: 'bin bags', aisle: 'Added by you', tier: 'tracked', toBuy: null, unit: null, why: 'added by hand', recipes: [] }, searchTerm: 'bin bags', basket: null },
        ],
      }),
    })
    render(withApi(api, <Shop />))

    expect(await screen.findByText('beef mince 300g')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'beef mince in basket' }))
    await waitFor(() => expect(api.basket).toHaveBeenCalled())
    await waitFor(() => expect(api.shopping).toHaveBeenCalledTimes(2))

    expect(events('Shopping List Viewed')).toEqual([['Shopping List Viewed', { itemCount: 2 }]])
    // Extras are typed by the owner, so their aisle is masked; the rest of the list is not.
    expect(screen.getByText('bin bags').closest('section')).toHaveAttribute('data-amp-mask')
    expect(screen.getByText('beef mince 300g').closest('section')).not.toHaveAttribute('data-amp-mask')
  })

  it('reports Up next once it has loaded, with how many recipes are on it', async () => {
    const api = fakeApi({ upNext: vi.fn().mockResolvedValue([{ recipeId: 'test-chilli', portions: 4, addedAt: '' }]) })
    render(withApi(api, <UpNext recipes={[chilli]} byId={byId} />))

    expect(await screen.findByText('Test Chilli')).toBeInTheDocument()
    expect(events('Up Next Viewed')).toEqual([['Up Next Viewed', { itemCount: 1 }]])
  })
})

describe('Meals analytics: settings', () => {
  it('keeps the minted token out of replay, and offers no cookie settings in a build without analytics', async () => {
    const api = fakeApi({ mintToken: vi.fn().mockResolvedValue({ token: { id: 't2' }, value: 'meals_secret' }) })
    render(withApi(api, <More onSignOut={vi.fn()} />))

    await userEvent.click(screen.getByRole('button', { name: 'Make a token' }))
    expect(await screen.findByText(/Authorization: Bearer meals_secret/)).toHaveAttribute('data-amp-block')
    expect(screen.queryByRole('heading', { name: 'Cookies' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cookie settings' })).not.toBeInTheDocument()
  })

  it('offers cookie settings between Claude Code and signing out when analytics is built in', async () => {
    configured.mockReturnValue(true)
    render(withApi(fakeApi(), <More onSignOut={vi.fn()} />))

    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent?.trim())
    expect(headings.indexOf('Cookies')).toBe(headings.indexOf('Claude Code') + 1)
    await userEvent.click(screen.getByRole('button', { name: 'Cookie settings' }))
    expect(openCookieSettings).toHaveBeenCalledTimes(1)
  })
})
