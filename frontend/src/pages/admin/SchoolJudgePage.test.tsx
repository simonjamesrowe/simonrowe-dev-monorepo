import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuth } from '../../auth/useAuth'
import {
  decideSchoolApproval,
  fetchSchoolJudgeSummary,
  runSchoolJudge,
  type SchoolJudgeSummary,
  type SchoolJudgementItem,
} from '../../services/adminApi'
import { SchoolJudgePage } from './SchoolJudgePage'

vi.mock('../../auth/useAuth', () => ({ useAuth: vi.fn() }))
vi.mock('../../services/adminApi', () => ({
  decideSchoolApproval: vi.fn(),
  fetchSchoolJudgeSummary: vi.fn(),
  runSchoolJudge: vi.fn(),
}))

const getAccessToken = vi.fn().mockResolvedValue('token')

function item(overrides: Partial<SchoolJudgementItem> = {}): SchoolJudgementItem {
  return {
    id: 'doc-1',
    title: 'Weekly Newsletter',
    sourceType: 'EMAIL',
    publishedAt: '2026-11-20T15:00:00Z',
    verdict: 'PUBLIC',
    confidence: 0.99,
    reason: 'A newsletter to all parents.',
    rule: null,
    followsDocumentId: null,
    senderAuthenticated: true,
    outcome: 'APPROVED',
    judgedAt: '2026-11-24T09:00:00Z',
    currentState: 'PUBLIC',
    overridden: false,
    preview: 'Dear Parents and Carers,',
    ...overrides,
  }
}

function summary(items: SchoolJudgementItem[]): SchoolJudgeSummary {
  return {
    mode: 'AUTO',
    model: 'gpt-6-luna',
    version: 'judge-v1',
    approved: 6,
    declined: 1,
    queued: 1,
    shadow: 0,
    overridden: 1,
    items,
  }
}

describe('SchoolJudgePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuth).mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
  })

  it('shows the 30-day counts and why each email was decided', async () => {
    vi.mocked(fetchSchoolJudgeSummary).mockResolvedValue(
      summary([
        item(),
        item({
          id: 'doc-2',
          title: 'RE: collection today',
          verdict: 'PRIVATE',
          confidence: 1,
          rule: 'reply',
          reason: 'A reply, forward or auto-reply',
          outcome: 'DECLINED',
          currentState: 'PRIVATE',
        }),
      ]),
    )

    render(<SchoolJudgePage />)

    const published = await screen.findByText('Published', { selector: 'dt' })
    expect(published.nextSibling).toHaveTextContent('6')
    expect(screen.getByText('public at 99%')).toBeInTheDocument()
    expect(screen.getByText('rule: reply')).toBeInTheDocument()
    expect(screen.getByText('A reply, forward or auto-reply')).toBeInTheDocument()
  })

  it('flags a decision the owner reversed and filters to them', async () => {
    vi.mocked(fetchSchoolJudgeSummary).mockResolvedValue(
      summary([
        item(),
        item({
          id: 'doc-2',
          title: 'Revoked one',
          currentState: 'WAITING',
          overridden: true,
        }),
      ]),
    )
    render(<SchoolJudgePage />)
    await screen.findByText('Revoked one')

    await userEvent.click(screen.getByRole('tab', { name: 'You overrode' }))

    expect(screen.queryByText('Weekly Newsletter')).not.toBeInTheDocument()
    expect(screen.getByText(/it is now waiting in Approvals/)).toBeInTheDocument()
  })

  it('makes a published email private in one click', async () => {
    vi.mocked(fetchSchoolJudgeSummary).mockResolvedValue(summary([item()]))
    vi.mocked(decideSchoolApproval).mockResolvedValue({} as never)
    render(<SchoolJudgePage />)
    const card = (await screen.findByText('Weekly Newsletter')).closest('li') as HTMLElement

    await userEvent.click(within(card).getByRole('button', { name: /Make private/ }))

    expect(decideSchoolApproval).toHaveBeenCalledWith(getAccessToken, 'doc-1', 'revoke')
    await waitFor(() => expect(fetchSchoolJudgeSummary).toHaveBeenCalledTimes(2))
  })

  it('runs the judge on demand and reports what it did', async () => {
    vi.mocked(fetchSchoolJudgeSummary).mockResolvedValue(summary([]))
    vi.mocked(runSchoolJudge).mockResolvedValue({
      ran: true,
      approved: 6,
      declined: 0,
      queued: 0,
      shadowed: 0,
      skipped: 0,
      detail: 'Published 6, kept 0 private, left 0 for you',
    })
    render(<SchoolJudgePage />)

    await userEvent.click(await screen.findByRole('button', { name: /Run the judge now/ }))

    expect(await screen.findByText('Published 6, kept 0 private, left 0 for you')).toBeInTheDocument()
  })

  it('says when an unverified sender stopped a publication', async () => {
    vi.mocked(fetchSchoolJudgeSummary).mockResolvedValue(
      summary([item({ senderAuthenticated: false, outcome: 'QUEUED', currentState: 'WAITING' })]),
    )

    render(<SchoolJudgePage />)

    expect(await screen.findByText(/Sender not verified/)).toBeInTheDocument()
  })
})
