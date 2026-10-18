import { describe, expect, it } from 'vitest'

import { rating, recipe } from './fixtures'
import { averageScore, COOKBOOK_AUTHOR, daysUntil, defaultSlot, describeChange, formatQty, scaledLine, describeUseBy, recipeMatches, searchTerms, sourceOf } from './logic'


describe('scaling', () => {
  it('keeps the printed line at the book portions and rewrites it otherwise', () => {
    const line = recipe().ingredients[0]
    expect(scaledLine(line, 1)).toBe('500g lean beef mince')
    expect(scaledLine(line, 1.5)).toBe('750g beef mince')
    expect(scaledLine(line, 3)).toBe('1.5kg beef mince')
    expect(scaledLine(recipe().ingredients[2], 2)).toBe('Salt to taste')
  })

  it('formats counts without a unit and large amounts in kg or L', () => {
    expect(formatQty(3, 'each')).toBe('3')
    expect(formatQty(1250, 'ml')).toBe('1.25L')
    expect(formatQty(null, 'g')).toBe('')
  })
})

describe('kitchen wording', () => {
  const friday = new Date(2026, 9, 16, 12)

  it('says how long until a use-by date, and how urgent it is', () => {
    expect(describeUseBy('2026-10-15', friday)).toEqual({ text: '1d past', tone: 'past' })
    expect(describeUseBy('2026-10-16', friday)).toEqual({ text: 'today', tone: 'now' })
    expect(describeUseBy('2026-10-17', friday)).toEqual({ text: 'tomorrow', tone: 'now' })
    expect(describeUseBy('2026-10-19', friday)).toEqual({ text: '3 days', tone: 'soon' })
    expect(describeUseBy('2026-10-30', friday)?.tone).toBe('')
    expect(describeUseBy(null, friday)).toBeNull()
    expect(daysUntil('2026-10-18', friday)).toBe(2)
  })

  it('describes each kind of change in a line', () => {
    expect(describeChange({ name: 'egg', action: 'add', quantity: 12, unit: 'each' })).toBe('+ 12 egg')
    expect(describeChange({ name: 'beef mince', action: 'use', quantity: 250, unit: 'g' })).toBe('− 250g beef mince')
    expect(describeChange({ name: 'milk', action: 'set', quantity: 1500, unit: 'ml' })).toBe('milk → 1.5L')
    expect(describeChange({ name: 'oats', action: 'status', status: 'low' })).toBe('oats: low')
    expect(describeChange({ name: 'oats', action: 'remove' })).toBe('removed oats')
  })

  it('averages every rating of a recipe and starts breakfast before 11', () => {
    expect(averageScore('test-chilli', [rating({ score: 5 }), rating({ id: 'r2', score: 2 })])).toBe(3.5)
    expect(averageScore('none', [])).toBeNull()
    expect(defaultSlot(new Date(2026, 9, 17, 8))).toBe('breakfast')
    expect(defaultSlot(new Date(2026, 9, 17, 13))).toBe('main')
  })
})

describe('sourceOf', () => {
  const at = (url: string | null, type: 'BOOK' | 'LINK' | 'PHOTO' = 'LINK') => recipe({ source: { type, book: null, pages: [], url } })

  it('names the author for a cookbook recipe, never the book', () => {
    expect(sourceOf(recipe())).toEqual({ label: COOKBOOK_AUTHOR, url: null })
  })

  it('names known sites by parsed host, subdomains included', () => {
    expect(sourceOf(at('https://www.bbcgoodfood.com/recipes/x'))?.label).toBe('BBC Good Food')
    expect(sourceOf(at('https://cooking.nytimes.com/recipes/1'))?.label).toBe('NYT Cooking')
  })

  it('is not fooled by a lookalike host', () => {
    expect(sourceOf(at('https://bbcgoodfood.com.example.net/x'))?.label).toBe('bbcgoodfood.com.example.net')
    expect(sourceOf(at('https://notbbcgoodfood.com/x'))?.label).toBe('notbbcgoodfood.com')
  })

  it('shows nothing for a missing or unsafe address, and "Your photo" for a photo', () => {
    expect(sourceOf(at(null))).toBeNull()
    expect(sourceOf(at('javascript:alert(1)'))).toBeNull()
    expect(sourceOf(at('not a url'))).toBeNull()
    expect(sourceOf(at(null, 'PHOTO'))?.label).toBe('Your photo')
  })
})

describe('recipe search', () => {
  const stirFry = recipe({
    name: 'Chicken Stir Fry', mainProtein: 'chicken',
    ingredients: [
      { text: '200g tenderstem broccoli', name: 'tenderstem broccoli', quantity: 200, unit: 'g' },
      { text: '1 bag broccoli florets', name: 'frozen veg', quantity: 1, unit: 'each' },
      { text: '3 tomatoes', name: 'tomato', quantity: 3, unit: 'each' },
    ],
  })
  const find = (q: string) => recipeMatches(stirFry, searchTerms(q))

  it('finds an ingredient by its catalogue name or as the recipe prints it', () => {
    expect(find('broccoli')).toBe(true)
    expect(find('florets')).toBe(true)
    expect(find('BROCCOLI')).toBe(true)
  })

  it('finds a plural search in a singular ingredient', () => {
    expect(find('tomatoes')).toBe(true)
    expect(find('chickens')).toBe(true)
  })

  it('needs every word to match', () => {
    expect(find('chicken broccoli')).toBe(true)
    expect(find('beef broccoli')).toBe(false)
  })

  it('matches everything on an empty search', () => {
    expect(find('   ')).toBe(true)
  })
})
