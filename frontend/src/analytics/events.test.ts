import { describe, expect, it } from 'vitest'

import { EVENTS, ID_PATTERN, sanitiseProperties, type EventName, type PropertyKind } from './events'

const KINDS = new Set(['id', 'count', 'duration', 'flag'])

/** Property names that would only ever exist to carry something personal or typed. */
const FORBIDDEN_NAMES = /question|query|text|message|note|title|name|email|amount|price|pence|child|body/i

describe('the event schema', () => {
  const properties = Object.entries(EVENTS).flatMap(([event, props]) =>
    Object.entries(props as Record<string, PropertyKind>).map(([key, kind]) => ({ event, key, kind })),
  )

  it.each(properties)('$event, $key: is an enum, an id, a number or a flag, never free text', ({ kind }) => {
    if (Array.isArray(kind)) {
      expect(kind.length).toBeGreaterThan(0)
      for (const value of kind) expect(value).toMatch(ID_PATTERN)
    } else {
      expect(KINDS.has(kind as string)).toBe(true)
    }
  })

  it.each(properties)('$event, $key: is not named for personal or typed content', ({ key }) => {
    expect(key).not.toMatch(FORBIDDEN_NAMES)
  })
})

describe('sanitiseProperties', () => {
  it('keeps values that fit their declared kind', () => {
    expect(
      sanitiseProperties('Chat Answer Received', { toolCount: 3, latencyMs: 820, failed: false }),
    ).toEqual({ toolCount: 3, latencyMs: 820, failed: false })
  })

  it.each([
    ['a sentence', 'what is your email'],
    ['an email address', 'someone@example.com'],
    ['a path', '../etc/passwd'],
    ['an empty string', ''],
    ['something too long', 'x'.repeat(129)],
  ])('drops an id that is %s', (_, value) => {
    expect(sanitiseProperties('Blog Viewed', { blogId: value })).toEqual({})
  })

  it('accepts a Mongo id and a slug', () => {
    expect(sanitiseProperties('Blog Viewed', { blogId: '66f1c0ffee12ab34cd56ef78' })).toEqual({
      blogId: '66f1c0ffee12ab34cd56ef78',
    })
    expect(sanitiseProperties('Portfolio Project Viewed', { slug: 'term-time' })).toEqual({ slug: 'term-time' })
  })

  it('drops an enum value the schema does not list', () => {
    expect(sanitiseProperties('Chat Opened', { source: 'somewhere-else' })).toEqual({})
  })

  it.each([[-1], [Number.NaN], [Number.POSITIVE_INFINITY], ['3']])('drops a count of %s', (value) => {
    expect(sanitiseProperties('Tour Step Viewed', { step: value })).toEqual({})
  })

  it('drops properties the event does not declare', () => {
    expect(sanitiseProperties('Contact Form Submitted', { email: 'a@b.com' })).toEqual({})
  })

  it('treats anything that is not an object as no properties', () => {
    expect(sanitiseProperties('Chat Question Sent' as EventName, undefined)).toEqual({})
    expect(sanitiseProperties('Chat Question Sent' as EventName, 'hello')).toEqual({})
  })
})
