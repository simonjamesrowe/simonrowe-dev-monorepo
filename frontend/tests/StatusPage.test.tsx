import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { StatusPage } from '../src/pages/StatusPage'
import type { PlatformStatus, Release, ServiceVersion } from '../src/types/platform'

vi.mock('../src/services/analytics', () => ({ trackPageView: vi.fn() }))

vi.mock('../src/config/version', () => ({
  FRONTEND_COMMIT: '840c311abcdef0123456789abcdef0123456789a',
  FRONTEND_SHORT_COMMIT: '840c311',
  FRONTEND_BUILD_TIME: '2026-08-26T14:02:11Z',
  frontendServiceVersion: () => ({
    name: 'frontend',
    commit: '840c311abcdef0123456789abcdef0123456789a',
    shortCommit: '840c311',
    commitSubject: null,
    commitTime: '2026-08-26T14:02:11Z',
    startedAt: null,
    reachable: true,
  }),
}))

const { mockStatus, mockReleases } = vi.hoisted(() => ({
  mockStatus: vi.fn(),
  mockReleases: vi.fn(),
}))

vi.mock('../src/hooks/usePlatformStatus', () => ({ usePlatformStatus: mockStatus }))
vi.mock('../src/hooks/useReleases', () => ({ useReleases: mockReleases }))

const BACKEND: ServiceVersion = {
  name: 'backend',
  commit: '840c311abcdef0123456789abcdef0123456789a',
  shortCommit: '840c311',
  commitSubject: 'docs: overhaul the README',
  commitTime: '2026-08-26T14:02:11Z',
  startedAt: '2026-08-24T09:15:03Z',
  reachable: true,
}

const STATUS: PlatformStatus = {
  services: [
    BACKEND,
    { ...BACKEND, name: 'software-factory' },
    {
      name: 'deployer',
      commit: 'unknown',
      shortCommit: 'dev',
      commitSubject: null,
      commitTime: null,
      startedAt: null,
      reachable: false,
    },
  ],
  components: [
    { name: 'mongodb', image: 'mongo', tag: '8', floating: false },
    { name: 'alloy', image: 'grafana/alloy', tag: 'latest', floating: true },
  ],
}

const RELEASES: Release[] = [
  {
    sha: '840c311abcdef0123456789abcdef0123456789a',
    shortSha: '840c311',
    type: 'docs',
    subject: 'docs: overhaul the README (#118)',
    commitTime: '2026-08-26T14:02:11Z',
    running: true,
    summary: 'The README was rewritten to explain the architecture.',
    summaryStatus: 'READY',
  },
  {
    sha: '39e0f7aabcdef0123456789abcdef0123456789a',
    shortSha: '39e0f7a',
    type: 'feat',
    subject: 'feat: deploy automatically on merge to main (#116)',
    commitTime: '2026-08-25T10:00:00Z',
    running: false,
    summary: null,
    summaryStatus: 'PENDING',
  },
  {
    sha: 'deadbeefabcdef0123456789abcdef0123456789a',
    shortSha: 'deadbee',
    type: 'fix',
    subject: 'fix: patch a broken thing (#120)',
    commitTime: '2026-08-20T10:00:00Z',
    running: false,
    summary: null,
    summaryStatus: 'FAILED',
  },
]

function renderPage() {
  render(
    <MemoryRouter>
      <StatusPage />
    </MemoryRouter>,
  )
}

describe('StatusPage', () => {
  beforeEach(() => {
    mockStatus.mockReturnValue({ status: STATUS, loading: false, error: null, retry: vi.fn() })
    mockReleases.mockReturnValue({
      releases: RELEASES,
      loading: false,
      error: null,
      retry: vi.fn(),
    })
  })

  it('renders a card for every reported service plus the frontend', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('backend')).toBeInTheDocument()
    })
    expect(screen.getByText('frontend')).toBeInTheDocument()
    expect(screen.getByText('software-factory')).toBeInTheDocument()
    expect(screen.getByText('deployer')).toBeInTheDocument()

    // Backend first, frontend adjacent second: the two versions that most often drift
    // sit next to each other. A future reorder of the services array should fail here.
    const names = Array.from(document.querySelectorAll('.service-card__name')).map(
      (node) => node.textContent,
    )
    expect(names).toEqual(['backend', 'frontend', 'software-factory', 'deployer'])
  })

  it('shows an unreachable service as not reporting rather than hiding it', () => {
    renderPage()

    expect(screen.getByText(/not reporting/i)).toBeInTheDocument()
  })

  it('warns when the frontend and backend SHAs differ', () => {
    mockStatus.mockReturnValue({
      status: {
        ...STATUS,
        services: [{ ...BACKEND, commit: 'aaaaaaabbbbbb', shortCommit: 'aaaaaaa' }],
      },
      loading: false,
      error: null,
      retry: vi.fn(),
    })

    renderPage()

    expect(screen.getByRole('alert')).toHaveTextContent(/different/i)
  })

  it('does not warn when every first-party SHA matches', () => {
    renderPage()

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('lists third-party components with their tags', () => {
    renderPage()

    // Scoped to the component row rather than a bare getByText('8'): a formatted date
    // elsewhere on the page can contain "8" and getByText throws on multiple matches.
    const row = screen.getByText('mongodb').closest('.component-table__row')
    expect(row).not.toBeNull()
    expect(row).toHaveTextContent('mongo')
    expect(row).toHaveTextContent('8')
  })

  it('labels a floating tag rather than presenting it as a version', () => {
    renderPage()

    // Scoped to the floating-badge element rather than a page-wide /floating/i: the
    // components note explaining the badge uses the same word, and a page-wide query
    // would match both.
    const row = screen.getByText('alloy').closest('.component-table__row')
    expect(row).not.toBeNull()
    const badge = row?.querySelector('.component-table__tag--floating')
    expect(badge).not.toBeNull()
    expect(badge).toHaveTextContent(/floating/i)
  })

  it('renders the AI release note for a ready release', () => {
    renderPage()

    expect(
      screen.getByText('The README was rewritten to explain the architecture.'),
    ).toBeInTheDocument()
  })

  it('renders a pending release from its subject with a pending note', () => {
    renderPage()

    // Scoped to this release's own entry rather than a page-wide /summary pending/i:
    // the FAILED fixture below also renders (or doesn't) a pending note, so a page-wide
    // query would match more than this one release.
    const entry = screen
      .getByText('feat: deploy automatically on merge to main (#116)')
      .closest('.release')
    expect(entry).not.toBeNull()
    expect(within(entry as HTMLElement).getByText(/summary pending/i)).toBeInTheDocument()
  })

  it('renders a FAILED release from its subject with no pending note', () => {
    renderPage()

    const entry = screen.getByText('fix: patch a broken thing (#120)').closest('.release')
    expect(entry).not.toBeNull()
    expect(within(entry as HTMLElement).queryByText(/summary pending/i)).not.toBeInTheDocument()
  })

  it('badges the running release', () => {
    renderPage()

    // Scoped to the running release's own entry rather than a page-wide /running now/i:
    // the releases note explaining the badge also says "the one running now", and a
    // page-wide query would match both.
    const entry = screen
      .getByText('docs: overhaul the README (#118)')
      .closest('.release')
    expect(entry).not.toBeNull()
    expect(within(entry as HTMLElement).getByText(/running now/i)).toBeInTheDocument()
  })

  it('links each release to its GitHub commit', () => {
    renderPage()

    // Several links share the short SHA — the backend card, the frontend card and this
    // release entry all render 840c311 — so scope the query to the release list rather
    // than using getByRole, which throws on multiple matches.
    const releaseLink = screen
      .getByText('docs: overhaul the README (#118)')
      .closest('.release')
      ?.querySelector('.release__sha')

    expect(releaseLink).toHaveAttribute(
      'href',
      'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/commit/840c311abcdef0123456789abcdef0123456789a',
    )
  })

  it('says history is published rather than implying it was deployed', () => {
    renderPage()

    expect(screen.getByText(/published/i)).toBeInTheDocument()
  })

  it('shows an empty-history message rather than a blank section', () => {
    mockReleases.mockReturnValue({ releases: [], loading: false, error: null, retry: vi.fn() })

    renderPage()

    expect(screen.getByText(/no release history yet/i)).toBeInTheDocument()
  })

  it('shows an error with a retry when the status request fails', () => {
    mockStatus.mockReturnValue({
      status: null,
      loading: false,
      error: 'Unable to load platform status (503).',
      retry: vi.fn(),
    })

    renderPage()

    expect(screen.getByText(/unable to load platform status/i)).toBeInTheDocument()
  })

  it('shows a loading indicator while the status is in flight', () => {
    mockStatus.mockReturnValue({ status: null, loading: true, error: null, retry: vi.fn() })

    renderPage()

    // LoadingIndicator's exact markup decides this assertion. Read
    // frontend/src/components/common/LoadingIndicator.tsx and match how an existing test
    // asserts on it (grep the tests directory for LoadingIndicator) rather than assuming
    // it renders the word "loading".
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })
})
