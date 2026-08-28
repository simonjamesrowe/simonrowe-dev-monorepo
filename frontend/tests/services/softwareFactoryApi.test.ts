import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  fetchRunProgress,
  fetchSoftwareFactoryStatus,
  startDeploy,
  startFeedback,
  startPlatformBackup,
  startVulnerabilityScan,
} from '../../src/services/softwareFactoryApi'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as Response
}

describe('softwareFactoryApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    getAccessToken.mockClear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const fetchMock = () => vi.mocked(globalThis.fetch)

  it('sends the admin bearer token and never a factory token', async () => {
    // The whole point of the backend proxy: the browser holds an Auth0 token and nothing else.
    // A factory credential reaching this layer would be visible in the network tab.
    fetchMock().mockResolvedValue(jsonResponse({ modules: [] }))

    await fetchSoftwareFactoryStatus(getAccessToken)

    const [, init] = fetchMock().mock.calls[0]
    const headers = init?.headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer test-token')
    expect(Object.keys(headers).join(',')).not.toContain('Factory-Token')
  })

  it('requests the status from the admin-protected path', async () => {
    fetchMock().mockResolvedValue(jsonResponse({ modules: [] }))

    await fetchSoftwareFactoryStatus(getAccessToken)

    expect(String(fetchMock().mock.calls[0][0]))
      .toContain('/api/admin/software-factory/status')
  })

  it('posts a pull request number to start feedback', async () => {
    fetchMock().mockResolvedValue(jsonResponse({ workflowId: 'review-feedback-42' }))

    await startFeedback(getAccessToken, 42)

    const [url, init] = fetchMock().mock.calls[0]
    expect(String(url)).toContain('/feedback')
    expect(init?.method).toBe('POST')
    expect(init?.body).toBe(JSON.stringify({ pullNumber: 42 }))
  })

  it('posts an empty body to start a vulnerability scan', async () => {
    fetchMock().mockResolvedValue(jsonResponse({ workflowId: 'cve-scan-manual-1' }))

    await startVulnerabilityScan(getAccessToken)

    expect(String(fetchMock().mock.calls[0][0])).toContain('/vulnerability-scans')
    expect(fetchMock().mock.calls[0][1]?.method).toBe('POST')
  })

  it('carries the dry-run mode on a platform backup', async () => {
    fetchMock().mockResolvedValue(jsonResponse({ workflowId: 'platform-backup-manual' }))

    await startPlatformBackup(getAccessToken, true)

    expect(fetchMock().mock.calls[0][1]?.body).toBe(JSON.stringify({ dryRun: true }))
  })

  it('sends both the commit and the typed confirmation on a deploy', async () => {
    // The commit is sent so the server can prove the two agree; the server deploys its own.
    fetchMock().mockResolvedValue(jsonResponse({ workflowId: 'deploy-prod' }))

    await startDeploy(getAccessToken, 'abc1234', 'REDEPLOY abc1234')

    expect(fetchMock().mock.calls[0][1]?.body)
      .toBe(JSON.stringify({ frontendCommit: 'abc1234', confirmation: 'REDEPLOY abc1234' }))
  })

  it('encodes the workflow id when reading progress', async () => {
    fetchMock().mockResolvedValue(jsonResponse({ workflowId: 'a b', terminal: true }))

    await fetchRunProgress(getAccessToken, 'a b')

    expect(String(fetchMock().mock.calls[0][0])).toContain('/runs/a%20b')
  })

  it('surfaces the backend message rather than a bare status code', async () => {
    fetchMock().mockResolvedValue(
      jsonResponse({ message: 'That run is already in progress' }, 409),
    )

    await expect(startPlatformBackup(getAccessToken, false))
      .rejects.toThrow('That run is already in progress')
  })

  it('falls back to the status when the error body is not json', async () => {
    fetchMock().mockResolvedValue({
      ok: false,
      status: 503,
      json: () => Promise.reject(new Error('not json')),
    } as unknown as Response)

    await expect(startVulnerabilityScan(getAccessToken))
      .rejects.toThrow('Request failed (503).')
  })
})
