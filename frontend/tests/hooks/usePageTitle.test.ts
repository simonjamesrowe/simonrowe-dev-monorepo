import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SITE_TITLE, usePageTitle } from '../../src/hooks/usePageTitle'

describe('usePageTitle', () => {
  it('uses the bare site title when no page name is given', () => {
    renderHook(() => usePageTitle())
    expect(document.title).toBe(SITE_TITLE)
  })

  it('suffixes the site name onto a page name', () => {
    renderHook(() => usePageTitle('Blog'))
    expect(document.title).toBe('Blog · Simon Rowe')
  })

  it('treats a blank page name as no page name', () => {
    renderHook(() => usePageTitle('   '))
    expect(document.title).toBe(SITE_TITLE)
  })

  it('updates when the page name arrives after the first render', () => {
    // The pattern used by pages whose title depends on fetched data.
    const { rerender } = renderHook(({ title }: { title?: string }) => usePageTitle(title), {
      initialProps: { title: undefined as string | undefined },
    })
    expect(document.title).toBe(SITE_TITLE)

    rerender({ title: 'Event sourcing without the ceremony' })
    expect(document.title).toBe('Event sourcing without the ceremony · Simon Rowe')
  })
})
