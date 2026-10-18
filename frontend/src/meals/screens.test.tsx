import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { rating, recipe } from './fixtures'
import { Home } from './Home'
import { Kitchen } from './Kitchen'
import { Library } from './Library'
import { More } from './More'
import { RecipeEditor } from './RecipeEditor'
import { RecipePage } from './RecipePage'
import { Shop } from './Shop'
import { fakeApi, withApi } from './testing'
import type { Coverage, Favourite, Ingredient, InventoryItem } from './types'
import { UpNext } from './UpNext'

const chilli = recipe({ photo: 'abc.jpg' })
const oats = recipe({
  id: 'test-oats', name: 'Test Oats', category: 'breakfast', vegetarian: true, freezable: false, servings: 1,
  mainProtein: null, ingredients: [{ text: '40g oats', name: 'oats', quantity: 40, unit: 'g' }],
})
const byId = new Map([[chilli.id, chilli], [oats.id, oats]])
const ingredients = new Map<string, Ingredient>([
  ['beef mince', { name: 'beef mince', unit: 'g', tier: 'tracked', aisle: 'Meat & fish', assumed: false }],
  ['oats', { name: 'oats', unit: 'g', tier: 'staple', aisle: 'Cupboard', assumed: false }],
  ['salt', { name: 'salt', unit: null, tier: 'staple', aisle: 'Cupboard', assumed: true }],
])
const have: Coverage = { ratio: 1, missing: [], shortOf: [], unknown: [], lines: { 'beef mince': 'HAVE', onion: 'HAVE' } }
const twoShort: Coverage = { ratio: 0.5, missing: ['onion'], shortOf: ['beef mince'], unknown: [], lines: { 'beef mince': 'SHORT', onion: 'MISSING' } }
const mince: InventoryItem = { id: 'o|beef mince', name: 'beef mince', tier: 'tracked', quantity: 500, unit: 'g', status: null, useBy: '2026-10-17', updatedAt: '' }
const heart = (recipeId: string): Favourite => ({ id: `me|${recipeId}`, recipeId, addedAt: '2026-10-18T09:00:00Z' })
const oatsStaple: InventoryItem = { id: 'o|oats', name: 'oats', tier: 'staple', quantity: null, unit: null, status: 'low', useBy: null, updatedAt: '' }

beforeEach(() => {
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:photo') }))
  vi.stubGlobal('scrollTo', vi.fn())
})
afterEach(() => vi.unstubAllGlobals())

describe('cook screen', () => {
  it('points an empty library at the More page', () => {
    render(withApi(fakeApi(), <Home recipes={[]} byId={new Map()} />))
    expect(screen.getByRole('link', { name: 'More' })).toHaveAttribute('href', '/more')
  })

  it('asks what is in when the kitchen is empty', async () => {
    render(withApi(fakeApi(), <Home recipes={[chilli]} byId={byId} now={new Date(2026, 9, 17, 19)} />))
    expect(await screen.findByText("Tell me what's in")).toBeInTheDocument()
  })

  it('offers favourites, not near misses, while nothing fresh is in', async () => {
    const api = fakeApi({
      kitchen: vi.fn().mockResolvedValue([oatsStaple]),
      favourites: vi.fn().mockResolvedValue([heart('test-chilli')]),
      suggestions: vi.fn().mockResolvedValue([
        { recipeId: 'test-oats', score: 30, coverage: twoShort, reasons: [], expiring: [] },
      ]),
    })
    render(withApi(api, <Home recipes={[chilli, oats]} byId={byId} now={new Date(2026, 9, 17, 19)} />))

    expect(await screen.findByText("Tell me what's in")).toBeInTheDocument()
    const favourites = (await screen.findByRole('heading', { name: 'Your favourites' })).closest('section')!
    expect(await within(favourites).findByRole('link', { name: /Test Chilli/ })).toHaveAttribute('href', '/recipes/test-chilli')
    expect(screen.queryByRole('heading', { name: 'One or two things short' })).not.toBeInTheDocument()
  })

  it('splits suggestions into cookable now and near misses, and queues one', async () => {
    const api = fakeApi({
      kitchen: vi.fn().mockResolvedValue([mince]),
      suggestions: vi.fn().mockResolvedValue([
        { recipeId: 'test-chilli', score: 80, coverage: have, reasons: ['you have everything'], expiring: ['beef mince'] },
        { recipeId: 'test-oats', score: 30, coverage: twoShort, reasons: ['missing onion, beef mince'], expiring: [] },
      ]),
    })
    render(withApi(api, <Home recipes={[chilli, oats]} byId={byId} now={new Date(2026, 9, 17, 19)} />))

    const ready = (await screen.findByRole('heading', { name: 'You can cook these now' })).parentElement!
    expect(within(ready).getByText('Test Chilli')).toBeInTheDocument()
    expect(within(ready).getByText('Have it all')).toBeInTheDocument()
    const close = screen.getByRole('heading', { name: 'One or two things short' }).parentElement!
    expect(within(close).getByText('2 to get')).toBeInTheDocument()
    expect(screen.getByText(/Use soon: beef mince/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Add Test Chilli to Up next' }))
    await waitFor(() => expect(api.queue).toHaveBeenCalledWith('test-chilli'))
    await userEvent.click(screen.getByRole('tab', { name: 'Breakfast' }))
    await waitFor(() => expect(api.suggestions).toHaveBeenCalledWith('breakfast'))
  })
})

describe('library', () => {
  it('searches, filters by meal and in the filter sheet, and sorts', async () => {
    const api = fakeApi({
      kitchen: vi.fn().mockResolvedValue([mince]),
      coverage: vi.fn().mockResolvedValue({ 'test-chilli': have, 'test-oats': twoShort }),
    })
    render(withApi(api, <Library recipes={[chilli, oats]} ratings={[rating({ score: 4 })]} />, '/recipes'))
    expect(screen.getByText(/^2 recipes/)).toBeInTheDocument()
    expect(await screen.findByText('Have it all')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Search recipes'), 'mince')
    expect(screen.queryByText('Test Oats')).not.toBeInTheDocument()
    expect(screen.getByText(/1 of 2 recipes/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Clear search' }))
    await userEvent.click(screen.getByRole('button', { name: 'Breakfast' }))
    expect(screen.queryByText('Test Chilli')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'All' }))

    await userEvent.click(screen.getByRole('button', { name: /Filters/ }))
    const sheet = screen.getByRole('dialog', { name: 'Filters' })
    await userEvent.click(within(sheet).getByRole('button', { name: 'Vegetarian' }))
    await userEvent.click(within(sheet).getByRole('button', { name: 'Show 1 recipe' }))
    expect(screen.getByText(/1 of 2 recipes/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Remove Vegetarian' }))
    expect(screen.getByText(/^2 recipes/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Filters/ }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Beef' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Under 400' }))
    expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Show 0 recipes' })).toBeInTheDocument()
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Reset' }))
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    for (const sort of ['protein', 'kcal', 'rating', 'cookable', 'az']) {
      await userEvent.selectOptions(screen.getByLabelText('Sort'), sort)
    }
    expect(screen.getByText('★ 4.0')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Import a recipe/ })).toHaveAttribute('href', '/recipes/new')
  })

  it('reads its filters from the address, so coming back keeps them', () => {
    render(withApi(fakeApi(), <Library recipes={[chilli, oats]} ratings={[]} />, '/recipes?diet=veg&q=oat'))
    expect(screen.getByLabelText('Search recipes')).toHaveValue('oat')
    expect(screen.getByText(/1 of 2 recipes/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove Vegetarian' })).toBeInTheDocument()
  })

  it('never mentions the books, and hides coverage until something fresh is in', async () => {
    const api = fakeApi({ coverage: vi.fn().mockResolvedValue({ 'test-chilli': have }) })
    render(withApi(api, <Library recipes={[chilli, oats]} ratings={[]} />, '/recipes'))
    expect(screen.queryByLabelText('Book')).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Most cookable now' })).not.toBeInTheDocument()
    await waitFor(() => expect(api.kitchen).toHaveBeenCalled())
    expect(screen.queryByText('Have it all')).not.toBeInTheDocument()
  })

  it('hearts a recipe and filters to favourites', async () => {
    const favourites = vi.fn().mockResolvedValueOnce([heart('test-oats')]).mockResolvedValue([heart('test-chilli'), heart('test-oats')])
    const api = fakeApi({ favourites, favourite: vi.fn().mockResolvedValue(heart('test-chilli')) })
    render(withApi(api, <Library recipes={[chilli, oats]} ratings={[]} />, '/recipes'))

    const oatsHeart = await screen.findByRole('button', { name: 'Favourite Test Oats' })
    await waitFor(() => expect(oatsHeart).toHaveAttribute('aria-pressed', 'true'))
    await userEvent.click(screen.getByRole('button', { name: /^Favourites$/ }))
    expect(screen.getByText(/1 of 2 recipes/)).toBeInTheDocument()
    expect(screen.queryByText('Test Chilli')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /^Favourites$/ }))

    const chilliHeart = screen.getByRole('button', { name: 'Favourite Test Chilli' })
    await userEvent.click(chilliHeart)
    await waitFor(() => expect(chilliHeart).toHaveAttribute('aria-pressed', 'true'))
    await waitFor(() => expect(api.favourite).toHaveBeenCalledWith('test-chilli'))
    await waitFor(() => expect(favourites).toHaveBeenCalledTimes(2))
    expect(chilliHeart).toHaveAttribute('aria-pressed', 'true')
  })

  it('says how to start when there are no favourites', async () => {
    render(withApi(fakeApi(), <Library recipes={[chilli]} ratings={[]} />, '/recipes?fav=1'))
    expect(await screen.findByText('No favourites yet')).toBeInTheDocument()
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
  })
})

describe('recipe page', () => {
  it('shows what is in, queues it, and records a cook with what it took', async () => {
    const api = fakeApi({
      recipeCoverage: vi.fn().mockResolvedValue({ ...twoShort, unknown: [] }),
      deductions: vi.fn().mockResolvedValue([
        { name: 'beef mince', tier: 'tracked', amount: 500, unit: 'g', ranOut: false },
        { name: 'onion', tier: 'tracked', amount: 2, unit: 'each', ranOut: false },
      ]),
      kitchen: vi.fn().mockResolvedValue([mince]),
    })
    render(withApi(api, <RecipePage recipe={chilli} ratings={[]} ingredients={ingredients} />))

    expect(await screen.findByText('To get: onion, beef mince.')).toBeInTheDocument()
    expect(screen.getByText(/Source:/)).toHaveTextContent('Source: Dave Fell')
    expect(screen.queryByText(/p\.12/)).not.toBeInTheDocument()
    expect(screen.getByText('not enough')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Add to Up next/ }))
    await waitFor(() => expect(api.queue).toHaveBeenCalledWith('test-chilli', 4))
    expect(screen.getByRole('link', { name: /Edit/ })).toHaveAttribute('href', '/recipes/test-chilli/edit')

    await userEvent.click(screen.getByRole('button', { name: /Cooked it/ }))
    const sheet = screen.getByRole('dialog', { name: 'Cooked Test Chilli' })
    expect(await within(sheet).findByText('beef mince')).toBeInTheDocument()
    expect(within(sheet).queryByText('onion')).not.toBeInTheDocument()
    await userEvent.click(within(sheet).getByRole('radio', { name: '4 stars' }))
    await userEvent.clear(within(sheet).getByLabelText('beef mince used'))
    await userEvent.type(within(sheet).getByLabelText('beef mince used'), '450')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.cooked).toHaveBeenCalledWith(expect.objectContaining({
      recipeId: 'test-chilli', portions: 4, score: 4,
      deductions: [{ name: 'beef mince', tier: 'tracked', amount: 450, unit: 'g', ranOut: false }],
    })))
  })

  it('walks through cook mode, shows past ratings and deletes one', async () => {
    const api = fakeApi()
    render(withApi(api, <RecipePage recipe={chilli} ratings={[rating({ notes: 'needs salt' })]} ingredients={ingredients} />))

    expect(screen.getByText('needs salt')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Cook mode/ }))
    const cook = screen.getByRole('dialog', { name: 'Cooking Test Chilli' })
    await userEvent.click(within(cook).getByRole('button', { name: /Start/ }))
    expect(within(cook).getByText('Step 1 of 3')).toBeInTheDocument()
    await userEvent.click(within(cook).getByRole('button', { name: /Next/ }))
    await userEvent.click(within(cook).getByRole('button', { name: /Back/ }))
    await userEvent.click(within(cook).getByRole('button', { name: 'Close cook mode' }))
    await userEvent.click(screen.getByRole('button', { name: 'Delete this rating' }))
    await waitFor(() => expect(api.deleteRating).toHaveBeenCalledWith('r1'))
  })
})

describe('kitchen', () => {
  it('reads a note into changes, and applies only the ticked ones', async () => {
    const api = fakeApi({
      readKitchen: vi.fn().mockResolvedValue({
        changes: [
          { name: 'egg', action: 'add', quantity: 12, unit: 'each', status: null, useBy: '2026-10-30', source: 'a dozen eggs' },
          { name: 'oats', action: 'status', quantity: null, unit: null, status: 'low', useBy: null, source: 'low on oats' },
        ],
        newIngredients: ['egg'],
      }),
    })
    render(withApi(api, <Kitchen ingredients={ingredients} />))

    await userEvent.type(screen.getByLabelText('What changed'), 'a dozen eggs, low on oats')
    await userEvent.click(screen.getByRole('button', { name: /Read it/ }))
    expect(await screen.findByText('+ 12 egg')).toBeInTheDocument()
    expect(screen.getByText('new ingredient')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: /oats: low/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Update kitchen' }))

    await waitFor(() => expect(api.applyChanges).toHaveBeenCalledWith([
      { name: 'egg', action: 'add', quantity: 12, unit: 'each', status: null, useBy: '2026-10-30', source: 'a dozen eggs' },
    ], 'quick-add'))
  })

  it('edits fresh items and staples in place', async () => {
    const api = fakeApi({ kitchen: vi.fn().mockResolvedValue([mince, oatsStaple]) })
    render(withApi(api, <Kitchen ingredients={ingredients} />))

    const qty = await screen.findByLabelText('beef mince quantity')
    await userEvent.clear(qty)
    await userEvent.type(qty, '300')
    await userEvent.tab()
    await waitFor(() => expect(api.applyChanges).toHaveBeenCalledWith(
      [{ name: 'beef mince', action: 'set', quantity: 300, unit: 'g' }]))

    await userEvent.click(screen.getByRole('tab', { name: /Cupboard/ }))
    await userEvent.click(screen.getByRole('radio', { name: 'out' }))
    await waitFor(() => expect(api.applyChanges).toHaveBeenCalledWith([{ name: 'oats', action: 'status', status: 'out' }]))
    await userEvent.click(screen.getByRole('button', { name: 'Remove oats' }))
    await waitFor(() => expect(api.removeItem).toHaveBeenCalledWith('oats'))
  })
})

describe('recipe page source', () => {
  it('links a web recipe to the site it came from, by name', () => {
    const web = recipe({ source: { type: 'LINK', book: null, pages: [], url: 'https://www.bbcgoodfood.com/recipes/chilli' } })
    render(withApi(fakeApi(), <RecipePage recipe={web} ratings={[]} ingredients={ingredients} />))
    expect(screen.getByRole('link', { name: /BBC Good Food/ })).toHaveAttribute('href', 'https://www.bbcgoodfood.com/recipes/chilli')
  })

  it('does not check an empty kitchen for the ingredients', async () => {
    const api = fakeApi()
    render(withApi(api, <RecipePage recipe={chilli} ratings={[]} ingredients={ingredients} />))
    await waitFor(() => expect(api.kitchen).toHaveBeenCalled())
    expect(api.recipeCoverage).not.toHaveBeenCalled()
    expect(screen.queryByText(/To get:/)).not.toBeInTheDocument()
  })
})

describe('up next', () => {
  it('changes portions and removes', async () => {
    const api = fakeApi({ upNext: vi.fn().mockResolvedValue([{ recipeId: 'test-chilli', portions: 4, addedAt: '' }]) })
    render(withApi(api, <UpNext recipes={[chilli, oats]} byId={byId} />))

    expect(await screen.findByText('Test Chilli')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'More portions' }))
    await waitFor(() => expect(api.queue).toHaveBeenCalledWith('test-chilli', 5))
    await userEvent.click(screen.getByRole('button', { name: 'Remove Test Chilli' }))
    await waitFor(() => expect(api.unqueue).toHaveBeenCalledWith('test-chilli'))
    expect(screen.getByRole('link', { name: /Shopping list/ })).toHaveAttribute('href', '/shop')
    expect(screen.queryByText(/Dave/)).not.toBeInTheDocument()
  })

  it('finds a recipe to add, and adds a favourite in one tap', async () => {
    const api = fakeApi({ favourites: vi.fn().mockResolvedValue([heart('test-chilli')]) })
    render(withApi(api, <UpNext recipes={[chilli, oats]} byId={byId} />))

    expect(await screen.findByText('Nothing planned yet')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Find a recipe to add'), 'oat')
    await userEvent.click(screen.getByRole('button', { name: 'Add Test Oats' }))
    await waitFor(() => expect(api.queue).toHaveBeenCalledWith('test-oats'))

    const favourites = screen.getByRole('heading', { name: /From your favourites/ }).closest('section')!
    await userEvent.click(await within(favourites).findByRole('button', { name: 'Add Test Chilli' }))
    await waitFor(() => expect(api.queue).toHaveBeenCalledWith('test-chilli'))
  })

  it('says how to get favourites here when there are none', async () => {
    render(withApi(fakeApi(), <UpNext recipes={[chilli]} byId={byId} />))
    expect(await screen.findByText(/Heart recipes on the/)).toBeInTheDocument()
  })
})

describe('shop', () => {
  const list = {
    upNextCount: 2,
    items: [
      { line: { name: 'beef mince', aisle: 'Meat & fish', tier: 'tracked', toBuy: 300, unit: 'g', why: 'you have 200g', recipes: ['Test Chilli'] }, searchTerm: 'beef mince 300g', basket: null },
      { line: { name: 'oats', aisle: 'Check the cupboard', tier: 'staple', toBuy: null, unit: null, why: 'not recorded', recipes: ['Test Oats'] }, searchTerm: 'oats', basket: 'Ocado' },
      { line: { name: 'bin bags', aisle: 'Added by you', tier: 'tracked', toBuy: null, unit: null, why: 'added by hand', recipes: [] }, searchTerm: 'bin bags', basket: null },
    ],
  }

  it('ticks items into the chosen shop\'s basket and puts a delivery in the kitchen', async () => {
    const api = fakeApi({ shopping: vi.fn().mockResolvedValue(list) })
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } })
    render(withApi(api, <Shop />))

    expect(await screen.findByText('beef mince 300g')).toBeInTheDocument()
    expect(screen.getByText('in Ocado basket · Test Oats')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: 'Deliveroo' }))
    await userEvent.click(screen.getByRole('button', { name: 'beef mince in basket' }))
    await waitFor(() => expect(api.basket).toHaveBeenCalledWith('beef mince', 'Deliveroo'))
    await userEvent.click(screen.getByRole('button', { name: 'oats in basket' }))
    await waitFor(() => expect(api.basket).toHaveBeenCalledWith('oats', null))

    await userEvent.click(screen.getByRole('button', { name: /Copy what's left/ }))
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('beef mince 300g\nbin bags')

    await userEvent.type(screen.getByLabelText('Add something else'), 'coffee{Enter}')
    await waitFor(() => expect(api.addExtra).toHaveBeenCalledWith('coffee'))
    await userEvent.click(screen.getByRole('button', { name: 'Remove bin bags' }))
    await waitFor(() => expect(api.removeExtra).toHaveBeenCalledWith('bin bags'))
    await userEvent.click(screen.getByRole('button', { name: /Put 1 ticked item in the kitchen/ }))
    await waitFor(() => expect(api.delivered).toHaveBeenCalled())
  })

  it('says what to do when nothing is queued', async () => {
    render(withApi(fakeApi(), <Shop />))
    expect(await screen.findByText('Nothing to buy yet')).toBeInTheDocument()
  })
})

describe('recipe editor', () => {
  const pancakes = {
    name: 'Test Pancakes', category: 'breakfast', section: null, servings: 1,
    perServing: { kcal: 465, protein: 46, carbs: 44, fat: 11 }, macrosEstimated: false,
    vegetarian: true, vegan: false, freezable: null, mainProtein: 'egg',
    ingredients: [
      { text: '40g rolled oats', name: 'oats', quantity: 40, unit: 'g' },
      { text: '150g 1% cottage cheese', name: 'cottage cheese', quantity: 150, unit: 'g' },
    ],
    method: ['Blend.', 'Cook.'], tips: [], swaps: [], sourceType: 'TEXT', sourceUrl: null, photo: null,
    newIngredients: [{ name: 'cottage cheese', unit: 'g', tier: 'tracked', aisle: 'Dairy & eggs' }],
  } as const

  it('reads pasted text, makes a photo while it is checked, and saves with nothing else to do', async () => {
    const api = fakeApi({
      readRecipeText: vi.fn().mockResolvedValue(pancakes),
      generatePhoto: vi.fn().mockResolvedValue({ name: 'made.jpg' }),
      createRecipe: vi.fn().mockResolvedValue({ id: 'test-pancakes' }),
    })
    render(withApi(api, <RecipeEditor knownIngredients={new Set(['oats'])} />))

    await userEvent.click(screen.getByLabelText('Recipe or link'))
    await userEvent.paste('TEST PANCAKES\n-40g rolled oats\n-150g 1% cottage cheese\n1. Blend. 2. Cook.')
    await userEvent.click(screen.getByRole('button', { name: 'Read it' }))

    expect(await screen.findByDisplayValue('Test Pancakes')).toBeInTheDocument()
    expect(api.readRecipeText).toHaveBeenCalledWith(expect.stringContaining('TEST PANCAKES'))
    expect(api.readRecipeLink).not.toHaveBeenCalled()
    expect(api.generatePhoto).toHaveBeenCalledWith('Test Pancakes', ['oats', 'cottage cheese'], expect.any(AbortSignal))
    expect(screen.getByRole('button', { name: 'Breakfast' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('150g 1% cottage cheese')).toBeInTheDocument()
    expect(screen.getByText(/1 new ingredient/)).toBeInTheDocument()
    expect(screen.getByText(/cottage cheese \(Dairy & eggs\)/)).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: /Make another/ })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.createRecipe).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Test Pancakes', sourceType: 'TEXT', photo: 'made.jpg',
      newIngredients: [{ name: 'cottage cheese', unit: 'g', tier: 'tracked', aisle: 'Dairy & eggs' }],
    })))
    expect(api.uploadPhoto).not.toHaveBeenCalled()
  })

  it('waits for the photo when saved while it is still being made', async () => {
    let finish: (v: { name: string }) => void = () => undefined
    const api = fakeApi({
      readRecipeText: vi.fn().mockResolvedValue(pancakes),
      generatePhoto: vi.fn().mockReturnValue(new Promise((resolve) => { finish = resolve })),
      createRecipe: vi.fn().mockResolvedValue({ id: 'test-pancakes' }),
    })
    render(withApi(api, <RecipeEditor knownIngredients={new Set(['oats'])} />))
    await userEvent.type(screen.getByLabelText('Recipe or link'), 'Pancakes, oats, cottage cheese')
    await userEvent.click(screen.getByRole('button', { name: 'Read it' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Finishing the photo…')).toBeInTheDocument()
    expect(api.createRecipe).not.toHaveBeenCalled()
    finish({ name: 'late.jpg' })
    await waitFor(() => expect(api.createRecipe).toHaveBeenCalledWith(expect.objectContaining({ photo: 'late.jpg' })))
  })

  it('reads a bare link as a page, and lets the owner change what the model chose', async () => {
    const api = fakeApi({
      readRecipeLink: vi.fn().mockResolvedValue({
        ...pancakes, sourceType: 'LINK', sourceUrl: 'https://www.example.com/o', macrosEstimated: true, photo: 'page.jpg',
      }),
      createRecipe: vi.fn().mockResolvedValue({ id: 'test-pancakes' }),
    })
    render(withApi(api, <RecipeEditor knownIngredients={new Set(['oats'])} />))

    await userEvent.type(screen.getByLabelText('Recipe or link'), 'example.com/o')
    await userEvent.click(screen.getByRole('button', { name: 'Read the page' }))
    expect(await screen.findByText(/Read from example.com/)).toBeInTheDocument()
    expect(api.readRecipeLink).toHaveBeenCalledWith('https://example.com/o')
    expect(screen.getByText('estimated')).toBeInTheDocument()
    expect(api.generatePhoto).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Main' }))
    await userEvent.click(screen.getByRole('button', { name: 'More portions' }))
    await userEvent.click(screen.getByRole('button', { name: 'Freezes' }))
    await userEvent.click(screen.getByRole('button', { name: /new ingredient/ }))
    await userEvent.click(within(screen.getByRole('group', { name: 'cottage cheese kept as' })).getByRole('button', { name: 'Cupboard staple' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.createRecipe).toHaveBeenCalledWith(expect.objectContaining({
      category: 'main', servings: 2, freezable: true, sourceType: 'LINK', photo: 'page.jpg', macrosEstimated: true,
      newIngredients: [{ name: 'cottage cheese', unit: 'g', tier: 'staple', aisle: 'Dairy & eggs' }],
    })))
  })

  it('says why a read failed and keeps what was pasted', async () => {
    const api = fakeApi({ readRecipeText: vi.fn().mockRejectedValue(new Error('The model timed out')) })
    render(withApi(api, <RecipeEditor knownIngredients={new Set()} />))
    await userEvent.type(screen.getByLabelText('Recipe or link'), 'Some pancakes')
    await userEvent.click(screen.getByRole('button', { name: 'Read it' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The model timed out')
    expect(screen.getByLabelText('Recipe or link')).toHaveValue('Some pancakes')
  })

  it('edits an existing recipe in place, filling in a typed line', async () => {
    const api = fakeApi({ updateRecipe: vi.fn().mockResolvedValue({ id: 'test-chilli' }) })
    render(withApi(api, <RecipeEditor recipe={chilli} knownIngredients={new Set(['beef mince', 'onion', 'salt'])} />))

    expect(api.generatePhoto).not.toHaveBeenCalled()
    await userEvent.clear(screen.getByLabelText('Name'))
    await userEvent.type(screen.getByLabelText('Name'), 'Better Chilli')
    await userEvent.click(screen.getByRole('button', { name: /Add an ingredient/ }))
    const index = chilli.ingredients.length + 1
    await userEvent.type(screen.getByLabelText(`Ingredient ${index}`), '2 tbsp tomato puree')
    expect(screen.getByLabelText(`Ingredient ${index} name`)).toHaveValue('tomato puree')
    expect(screen.getByLabelText(`Ingredient ${index} quantity`)).toHaveValue(30)
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.updateRecipe).toHaveBeenCalledWith('test-chilli', expect.objectContaining({
      name: 'Better Chilli',
      ingredients: expect.arrayContaining([{ text: '2 tbsp tomato puree', name: 'tomato puree', quantity: 30, unit: 'ml' }]),
      newIngredients: [{ name: 'tomato puree', unit: 'ml', tier: 'tracked', aisle: 'Other' }],
    })))
  })
})

describe('more', () => {
  it('makes a Claude Code token, shows the command once, and revokes one', async () => {
    const api = fakeApi({
      tokens: vi.fn().mockResolvedValue([{ id: 't1', label: 'Old', hint: 'meals_abcd', createdAt: '', lastUsedAt: null, revokedAt: null }]),
      mintToken: vi.fn().mockResolvedValue({ token: { id: 't2' }, value: 'meals_secret' }),
    })
    render(withApi(api, <More onSignOut={vi.fn()} />))

    await userEvent.click(screen.getByRole('button', { name: 'Make a token' }))
    expect(await screen.findByText(/Authorization: Bearer meals_secret/)).toBeInTheDocument()
    expect(screen.getByText(/claude mcp add --transport http meals/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Revoke Old' }))
    await waitFor(() => expect(api.revokeToken).toHaveBeenCalledWith('t1'))
  })

  it('uploads each bundle in turn and reports what each did', async () => {
    const api = fakeApi({
      importBundle: vi.fn()
        .mockResolvedValueOnce({ recipesAdded: 50, recipesExisting: 0, ingredientsAdded: 113, templatesAdded: 33, photosStored: 50, skipped: ['recipe x: no name'] })
        .mockRejectedValueOnce(new Error('Not a readable zip file')),
    })
    render(withApi(api, <More onSignOut={vi.fn()} />))

    await userEvent.upload(screen.getByLabelText(/Choose bundles/),
      [new File(['a'], 'one.zip', { type: 'application/zip' }), new File(['b'], 'two.zip', { type: 'application/zip' })])
    expect(await screen.findByText(/50 recipes added/)).toBeInTheDocument()
    expect(screen.getByText('1 skipped')).toBeInTheDocument()
    expect(await screen.findByText('Not a readable zip file')).toBeInTheDocument()
  })
})
