import { describe, expect, it } from 'vitest'

import {
  buildAllowlist,
  classifyLink,
  isAllowedImage,
  isInternalRoute,
} from '../../../src/components/chat/linkPolicy'
import type { ChatBlock } from '../../../src/components/chat/chatTypes'

describe('linkPolicy', () => {
  describe('isInternalRoute', () => {
    it.each([
      '/',
      '/profile',
      '/experience',
      '/experience?job=job-1',
      '/experience?skillGroup=group-1',
      '/experience#roles',
      '/blogs',
      '/blogs/my-post-id',
      '/news-events',
      '/news-events#events',
    ])('accepts internal route %s', (href) => {
      expect(isInternalRoute(href)).toBe(true)
    })

    it.each([
      '/experience Macquarie Group,',
      '/unknown',
      '/blogs/one/two',
      'https://example.com',
      'javascript:alert(1)',
    ])('rejects non-internal route %s', (href) => {
      expect(isInternalRoute(href)).toBe(false)
    })
  })

  describe('classifyLink', () => {
    const allowlist = new Set<string>(['https://spring.io/blog/advisors'])

    it('classifies internal routes as internal', () => {
      expect(classifyLink('/experience?job=job-1', allowlist)).toBe('internal')
    })

    it('classifies an allowlisted https URL as external-allowed', () => {
      expect(classifyLink('https://spring.io/blog/advisors', allowlist)).toBe('external-allowed')
    })

    it('strips a non-allowlisted https URL', () => {
      expect(classifyLink('https://evil.example.com', allowlist)).toBe('strip')
    })

    it('strips a fabricated internal-looking destination with spaces', () => {
      expect(classifyLink('/experience Macquarie Group,', allowlist)).toBe('strip')
    })

    it('strips javascript: and data: schemes', () => {
      expect(classifyLink('javascript:alert(1)', allowlist)).toBe('strip')
      expect(classifyLink('data:text/html,<script>', allowlist)).toBe('strip')
    })

    it('strips plain http: (non-https) even if not allowlisted', () => {
      expect(classifyLink('http://spring.io/blog/advisors', allowlist)).toBe('strip')
    })

    it('strips an undefined href', () => {
      expect(classifyLink(undefined, allowlist)).toBe('strip')
    })
  })

  describe('isAllowedImage', () => {
    const allowlist = new Set<string>(['https://cdn.example.com/allowed.png'])

    it('allows uploads-origin images', () => {
      expect(isAllowedImage('/uploads/img/small.webp', allowlist)).toBe(true)
    })

    it('allows an allowlisted external image', () => {
      expect(isAllowedImage('https://cdn.example.com/allowed.png', allowlist)).toBe(true)
    })

    it('drops a non-allowlisted external image', () => {
      expect(isAllowedImage('https://cdn.example.com/other.png', allowlist)).toBe(false)
    })

    it('drops an undefined src', () => {
      expect(isAllowedImage(undefined, allowlist)).toBe(false)
    })
  })

  describe('buildAllowlist', () => {
    it('collects urls from blog, news, and event widget payloads', () => {
      const blocks: ChatBlock[] = [
        {
          kind: 'widget',
          widgetKind: 'blogs',
          payload: { posts: [{ title: 'A', url: '/blogs/a', imageUrl: '/uploads/a.webp' }] },
        },
        {
          kind: 'widget',
          widgetKind: 'news',
          payload: {
            articles: [{ title: 'N', originalUrl: 'https://spring.io/x', imageUrl: 'https://spring.io/x.png' }],
          },
        },
        {
          kind: 'widget',
          widgetKind: 'events',
          payload: { events: [{ title: 'E', originalUrl: 'https://lu.ma/e' }] },
        },
        { kind: 'text', content: 'ignored' },
      ]

      const allowlist = buildAllowlist(blocks, ['/uploads/avatar.webp'])

      expect(allowlist.has('/blogs/a')).toBe(true)
      expect(allowlist.has('/uploads/a.webp')).toBe(true)
      expect(allowlist.has('https://spring.io/x')).toBe(true)
      expect(allowlist.has('https://spring.io/x.png')).toBe(true)
      expect(allowlist.has('https://lu.ma/e')).toBe(true)
      expect(allowlist.has('/uploads/avatar.webp')).toBe(true)
    })
  })
})

describe('classifyLink origin boundary', () => {
  const SCHOOL = 'https://www.kilmorieschool.co.uk'

  it('allows a deep link on an allowed origin', () => {
    expect(classifyLink(`${SCHOOL}/enrichment`, new Set(), [SCHOOL])).toBe('external-allowed')
    expect(classifyLink(SCHOOL, new Set(), [SCHOOL])).toBe('external-allowed')
  })

  it('strips a domain-suffix forgery of an allowed origin', () => {
    // The prefix has no trailing slash, so a bare startsWith also matched
    // "kilmorieschool.co.uk.attacker.example" and rendered it as a clickable link wearing the
    // school's name. Answers are built from scraped pages and emailed PDFs, so a hostile URL
    // can genuinely reach the model's context.
    expect(
      classifyLink(`${SCHOOL}.attacker.example/phish`, new Set(), [SCHOOL]),
    ).toBe('strip')
    expect(
      classifyLink('https://www.kilmorieschool.co.uk.evil.test/', new Set(), [SCHOOL]),
    ).toBe('strip')
  })

  it('strips a different host that merely contains the allowed one', () => {
    expect(classifyLink('https://evil.test/https://www.kilmorieschool.co.uk', new Set(), [SCHOOL]))
      .toBe('strip')
  })

  it('a prefix carrying a path still narrows to that path', () => {
    // Origin matching alone would widen such a prefix to the whole host, which is the opposite
    // of what someone writing it intends.
    const scoped = `${SCHOOL}/letters/`
    expect(classifyLink(`${SCHOOL}/letters/autumn`, new Set(), [scoped])).toBe('external-allowed')
    expect(classifyLink(`${SCHOOL}/private/pay`, new Set(), [scoped])).toBe('strip')
  })

  it('an unparseable href is stripped rather than allowed', () => {
    expect(classifyLink('https://', new Set(), [SCHOOL])).toBe('strip')
    expect(classifyLink('not a url', new Set(), [SCHOOL])).toBe('strip')
  })
})
