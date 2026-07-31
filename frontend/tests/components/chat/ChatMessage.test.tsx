import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { ChatMessage } from '../../../src/components/chat/ChatMessage'
import type { ChatBlock } from '../../../src/components/chat/chatTypes'

function renderMessage(blocks: ChatBlock[]) {
  return render(
    <MemoryRouter>
      <ChatMessage role="assistant" blocks={blocks} />
    </MemoryRouter>,
  )
}

describe('ChatMessage link/image policy', () => {
  it('renders an internal blog link as a new-tab anchor with a relative href', () => {
    const blocks: ChatBlock[] = [
      { kind: 'text', content: 'See [my post](/blogs/streaming-chat).' },
    ]
    renderMessage(blocks)

    const link = screen.getByRole('link', { name: 'my post' })
    // Internal links keep their relative href but open in a new tab so the chat is not lost.
    expect(link.getAttribute('href')).toBe('/blogs/streaming-chat')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('renders an allowlisted external link (from a widget payload) as a new-tab anchor', () => {
    const blocks: ChatBlock[] = [
      {
        kind: 'widget',
        widgetKind: 'news',
        payload: { articles: [{ title: 'Advisors', originalUrl: 'https://spring.io/blog/advisors' }] },
      },
      { kind: 'text', content: 'Read [the article](https://spring.io/blog/advisors).' },
    ]
    renderMessage(blocks)

    const link = screen.getByRole('link', { name: 'the article' })
    expect(link).toHaveAttribute('href', 'https://spring.io/blog/advisors')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('strips a fabricated / non-allowlisted external link to plain text', () => {
    const blocks: ChatBlock[] = [
      { kind: 'text', content: 'Beware [evil](https://evil.example.com).' },
    ]
    renderMessage(blocks)

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText(/Beware/)).toBeInTheDocument()
    expect(screen.getByText(/evil/)).toBeInTheDocument()
  })

  it('linkifies a BARE internal URL in prose (safety net when the model forgets markdown syntax)', () => {
    const blocks: ChatBlock[] = [
      { kind: 'text', content: 'See the Y-Tree role — /experience?job=5eedd4803c8d74001e4497f5' },
    ]
    renderMessage(blocks)

    const link = screen.getByRole('link')
    expect(link.getAttribute('href')).toBe('/experience?job=5eedd4803c8d74001e4497f5')
    expect(link).toHaveAttribute('target', '_blank')
  })

  it('does not double-wrap a URL the model already put in markdown link syntax', () => {
    const blocks: ChatBlock[] = [
      { kind: 'text', content: 'See [Y-Tree](/experience?job=5eedd4803c8d74001e4497f5).' },
    ]
    renderMessage(blocks)

    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveTextContent('Y-Tree')
    expect(links[0].getAttribute('href')).toBe('/experience?job=5eedd4803c8d74001e4497f5')
  })

  it('hides every widget card, including the code example', () => {
    const blocks: ChatBlock[] = [
      { kind: 'text', content: 'Here is a snippet and my skills.' },
      { kind: 'widget', widgetKind: 'skills', payload: { groups: [{ id: 'g1', name: 'Java', skills: [{ name: 'Java 21' }] }] } },
      { kind: 'widget', widgetKind: 'code', payload: { examples: [{ id: 'c1', title: 'Kafka Consumer', code: 'class C {}' }] } },
    ]
    const { container } = renderMessage(blocks)

    // The code card used to be the one exception; it now goes the same way as the rest,
    // leaving the tool label as the only signal that the tool ran.
    expect(container.querySelector('.chat-widget--code')).toBeNull()
    expect(container.querySelector('.chat-widget--skills')).toBeNull()
    expect(screen.queryByText('Kafka Consumer')).not.toBeInTheDocument()
    expect(screen.queryByText('class C {}')).not.toBeInTheDocument()
    expect(screen.queryByText('Java 21')).not.toBeInTheDocument()

    // The prose answer itself is untouched.
    expect(screen.getByText('Here is a snippet and my skills.')).toBeInTheDocument()
  })

  it('renders an uploads-origin image and drops a non-allowlisted image', () => {
    const blocks: ChatBlock[] = [
      { kind: 'text', content: '![ok](/uploads/pic.webp) ![no](https://evil.example.com/x.png)' },
    ]
    const { container } = renderMessage(blocks)

    const imgs = container.querySelectorAll('.chat-message__image')
    expect(imgs).toHaveLength(1)
    expect(imgs[0].getAttribute('src')).toBe('/uploads/pic.webp')
    expect(imgs[0].getAttribute('loading')).toBe('lazy')
  })
})
