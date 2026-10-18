import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ContentSourcesAdmin } from '../../src/pages/admin/ContentSourcesAdmin'

vi.mock('../../src/services/adminApi', () => ({
  fetchContentSources: vi.fn(),
  updateContentSource: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import {
  fetchContentSources,
  updateContentSource,
  type AdminContentSource,
} from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

function source(overrides: Partial<AdminContentSource>): AdminContentSource {
  return {
    id: 'src-1',
    name: 'Claude Blog',
    baseUrl: 'https://claude.com/blog',
    feedUrl: null,
    sitemapUrl: null,
    sourceType: 'BLOG',
    scrapeStrategy: 'HTML_LISTING',
    active: true,
    lastFetchedAt: null,
    lastError: null,
    ...overrides,
  }
}

describe('ContentSourcesAdmin active toggles', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuth).mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    vi.mocked(fetchContentSources).mockResolvedValue([
      source({}),
      source({ id: 'src-2', name: 'Spring Blog', active: false }),
    ])
  })

  /** Every row's checkbox is named after its source, so a screen reader can tell them apart. */
  it('names each toggle after its source', async () => {
    render(<ContentSourcesAdmin />)

    expect(await screen.findByLabelText('Claude Blog active')).toBeChecked()
    expect(screen.getByLabelText('Spring Blog active')).not.toBeChecked()
  })

  it('flips the source reached by its label', async () => {
    vi.mocked(updateContentSource).mockResolvedValue(source({ id: 'src-2', name: 'Spring Blog', active: true }))
    render(<ContentSourcesAdmin />)

    fireEvent.click(await screen.findByLabelText('Spring Blog active'))

    await waitFor(() => expect(screen.getByLabelText('Spring Blog active')).toBeChecked())
    expect(updateContentSource).toHaveBeenCalledWith(getAccessToken, 'src-2', { active: true })
  })
})
