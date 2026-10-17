import { describe, expect, it } from 'vitest'

import { rating, recipe } from './fixtures'
import { averageScore, daysUntil, defaultSlot, describeChange, formatQty, scaledLine, describeUseBy } from './logic'


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
