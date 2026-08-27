import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ServiceVersionCard } from '../../../src/components/status/ServiceVersionCard'
import type { ServiceVersion } from '../../../src/types/platform'

const REACHABLE_KNOWN: ServiceVersion = {
  name: 'backend',
  commit: '840c311abcdef0123456789abcdef0123456789a',
  shortCommit: '840c311',
  commitSubject: 'docs: overhaul the README',
  commitTime: '2026-08-26T14:02:11Z',
  startedAt: '2026-08-24T09:15:03Z',
  reachable: true,
}

describe('ServiceVersionCard', () => {
  it('links the short commit to GitHub when the commit is known', () => {
    render(<ServiceVersionCard version={REACHABLE_KNOWN} />)

    const link = screen.getByText('840c311')
    expect(link.tagName).toBe('A')
    expect(link).toHaveAttribute(
      'href',
      'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/commit/840c311abcdef0123456789abcdef0123456789a',
    )
  })

  it('renders no link when a reachable service reports an unknown commit', () => {
    const version: ServiceVersion = {
      ...REACHABLE_KNOWN,
      commit: 'unknown',
      shortCommit: 'dev',
    }

    render(<ServiceVersionCard version={version} />)

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('dev')).toBeInTheDocument()
  })
})
