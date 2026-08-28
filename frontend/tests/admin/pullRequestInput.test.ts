import { describe, expect, it } from 'vitest'

import { parsePullNumber } from '../../src/pages/admin/pullRequestInput'

describe('parsePullNumber', () => {
  it('reads a bare number', () => {
    expect(parsePullNumber('130')).toBe(130)
    expect(parsePullNumber('  130  ')).toBe(130)
  })

  it('reads the # form GitHub renders', () => {
    expect(parsePullNumber('#130')).toBe(130)
    expect(parsePullNumber('simonjamesrowe/simonrowe-dev-monorepo#130')).toBe(130)
  })

  it('reads a pasted pull request URL', () => {
    // Copying the address bar is the natural gesture, so this is the likeliest input of all.
    expect(parsePullNumber('https://github.com/simonjamesrowe/simonrowe-dev-monorepo/pull/130'))
      .toBe(130)
  })

  it('reads a URL that points at a tab, an anchor or a query', () => {
    const base = 'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/pull/130'
    expect(parsePullNumber(`${base}/files`)).toBe(130)
    expect(parsePullNumber(`${base}/commits/abc123`)).toBe(130)
    expect(parsePullNumber(`${base}#issuecomment-5451467529`)).toBe(130)
    expect(parsePullNumber(`${base}?w=1`)).toBe(130)
    expect(parsePullNumber(`${base}/`)).toBe(130)
  })

  it('accepts the /pulls/ spelling as well as /pull/', () => {
    expect(parsePullNumber('https://github.com/o/r/pulls/7')).toBe(7)
  })

  it('rejects anything it cannot read as a pull request', () => {
    for (const input of ['', '   ', 'main', 'abc', '0', '#0', '-4', '1.5', 'pull/130']) {
      expect(parsePullNumber(input)).toBeNull()
    }
  })

  it('does not mistake an issue URL for a pull request', () => {
    // Filing the wrong number is worse than refusing: the review would run against a real but
    // unrelated pull request that happens to share the issue's number.
    expect(parsePullNumber('https://github.com/o/r/issues/130')).toBeNull()
  })

  it('refuses a number too large to be a safe integer', () => {
    expect(parsePullNumber('99999999999999999999')).toBeNull()
  })
})
