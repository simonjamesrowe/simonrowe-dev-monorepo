import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CodeExampleDrawer } from '../../../src/components/code/CodeExampleDrawer'

vi.mock('../../../src/services/codeExampleApi', () => ({
  fetchCodeExample: vi.fn(),
}))

import { fetchCodeExample } from '../../../src/services/codeExampleApi'

async function renderDrawer() {
  const onClose = vi.fn()
  const view = render(<CodeExampleDrawer codeExampleId="ex-1" onClose={onClose} />)
  await screen.findByText('Virtual threads')
  return { ...view, onClose }
}

describe('CodeExampleDrawer', () => {
  beforeEach(() => {
    vi.mocked(fetchCodeExample).mockResolvedValue({
      id: 'ex-1',
      title: 'Virtual threads',
      description: 'Loom in practice.',
      language: 'java',
      code: 'Thread.ofVirtual().start(task);',
      skills: ['Java'],
      createdAt: '',
      updatedAt: '',
    })
  })

  it('shows the example it loaded', async () => {
    await renderDrawer()

    expect(screen.getByText('Thread.ofVirtual().start(task);')).toBeInTheDocument()
    expect(screen.getByText('Loom in practice.')).toBeInTheDocument()
  })

  it('stays open when the click lands inside the drawer, on the code included', async () => {
    const { onClose } = await renderDrawer()

    fireEvent.click(screen.getByText('Thread.ofVirtual().start(task);'))
    fireEvent.click(screen.getByText('Virtual threads'))

    expect(onClose).not.toHaveBeenCalled()
  })

  it('closes on a click on the backdrop', async () => {
    const { container, onClose } = await renderDrawer()

    fireEvent.click(container.querySelector('.drawer-overlay')!)

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes from its Close button and on Escape', async () => {
    const { onClose } = await renderDrawer()

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    fireEvent.keyDown(document, { key: 'Escape' })

    expect(onClose).toHaveBeenCalledTimes(2)
  })
})
