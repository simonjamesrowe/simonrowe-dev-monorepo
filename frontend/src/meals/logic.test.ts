import { describe, expect, it } from 'vitest'

import { rating, recipe } from './fixtures'
import { averageScore, defaultSlot, formatQty, homeSections, scaledLine } from './logic'

const NOW = new Date('2026-10-17T19:00:00Z')

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

describe('home suggestions', () => {
  const chilli = recipe()
  const curry = recipe({ id: 'test-curry', name: 'Test Curry', mainProtein: 'chicken' })
  const pasta = recipe({ id: 'test-pasta', name: 'Test Pasta', mainProtein: 'beef' })
  const oats = recipe({ id: 'test-oats', name: 'Test Oats', category: 'breakfast', mainProtein: null })

  it('offers favourites not eaten in the last five days, best first', () => {
    const ratings = [
      rating({ recipeId: 'test-chilli', score: 5, at: '2026-10-01T18:00:00Z' }),
      rating({ id: 'r2', recipeId: 'test-curry', score: 4, at: '2026-10-15T18:00:00Z' }),
    ]
    const sections = homeSections([chilli, curry, pasta, oats], ratings, 'main', NOW)
    expect(sections.favourites.map((r) => r.id)).toEqual(['test-chilli'])
  })

  it('never suggests something marked not to make again', () => {
    const ratings = [rating({ score: 5, again: false })]
    expect(homeSections([chilli], ratings, 'main', NOW).favourites).toEqual([])
  })

  it('leads the untried with the protein you liked, and stays within the meal', () => {
    const ratings = [rating({ recipeId: 'test-chilli', score: 5 })]
    const { untried } = homeSections([chilli, curry, pasta, oats], ratings, 'main', NOW)
    expect(untried.map((r) => r.id)).toEqual(['test-pasta', 'test-curry'])
  })

  it('lists the last fortnight of cooking, newest first', () => {
    const ratings = [
      rating({ recipeId: 'test-chilli', at: '2026-10-10T18:00:00Z' }),
      rating({ id: 'r2', recipeId: 'test-oats', at: '2026-10-16T08:00:00Z' }),
      rating({ id: 'r3', recipeId: 'test-pasta', at: '2026-09-01T18:00:00Z' }),
    ]
    expect(homeSections([chilli, pasta, oats], ratings, 'main', NOW).recent.map((r) => r.id))
      .toEqual(['test-oats', 'test-chilli'])
  })

  it('averages every rating of a recipe and starts breakfast before 11', () => {
    expect(averageScore('test-chilli', [rating({ score: 5 }), rating({ id: 'r2', score: 2 })])).toBe(3.5)
    expect(averageScore('none', [])).toBeNull()
    expect(defaultSlot(new Date(2026, 9, 17, 8))).toBe('breakfast')
    expect(defaultSlot(new Date(2026, 9, 17, 13))).toBe('main')
  })
})
