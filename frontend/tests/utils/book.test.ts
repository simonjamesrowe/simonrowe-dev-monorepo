import { describe, expect, it } from 'vitest'

import { authorLine, purchaseLabel, shelvesOf, type Book } from '../../src/types/book'

const book = (slug: string, category: string): Book => ({
  slug, title: slug, authors: ['A'], category, summary: 's', displayOrder: 0,
})

describe('book helpers', () => {
  it('joins authors the way a person would write them', () => {
    expect(authorLine([])).toBe('')
    expect(authorLine(['Kent Beck'])).toBe('Kent Beck')
    expect(authorLine(['Jez Humble', 'David Farley'])).toBe('Jez Humble and David Farley')
    expect(authorLine(['A', 'B', 'C', 'D'])).toBe('A, B, C and D')
  })

  it('groups by category in first-appearance order, keeping each book in place', () => {
    const shelves = shelvesOf([book('a', 'X'), book('b', 'Y'), book('c', 'X')])
    expect(shelves.map(shelf => [shelf.category, shelf.books.map(b => b.slug)]))
      .toEqual([['X', ['a', 'c']], ['Y', ['b']]])
  })

  it('calls a link Amazon only when its parsed host is an Amazon storefront', () => {
    expect(purchaseLabel('https://www.amazon.co.uk/dp/B0')).toBe('Buy on Amazon')
    expect(purchaseLabel('https://amazon.com/dp/B0')).toBe('Buy on Amazon')
    expect(purchaseLabel('https://www.amazon.co.uk.attacker.example/dp/B0')).toBe('Buy the book')
    expect(purchaseLabel('https://shop.example/?ref=amazon.co.uk')).toBe('Buy the book')
    expect(purchaseLabel('not a url')).toBe('Buy the book')
  })
})
