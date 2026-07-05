import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ChatWidget } from './ChatWidgetRegistry'

describe('ChatWidgetRegistry', () => {
  it('renders a known skills widget', () => {
    render(<ChatWidget widgetKind="skills" payload={{
      groups: [{ name: 'Backend', skills: [{ name: 'Spring Boot', rating: 9 }] }],
    }} />)

    expect(screen.getByText('Backend')).toBeInTheDocument()
    expect(screen.getByText('Spring Boot')).toBeInTheDocument()
    expect(screen.getByText('9/10')).toBeInTheDocument()
  })

  it('renders a known news widget', () => {
    render(<ChatWidget widgetKind="news" payload={{
      articles: [{
        title: 'Spring AI News',
        sourceName: 'Spring Blog',
        originalUrl: 'https://spring.io/blog/news',
      }],
    }} />)

    expect(screen.getByText('Spring AI News')).toBeInTheDocument()
    expect(screen.getByText('Spring Blog')).toBeInTheDocument()
  })

  it('renders a known events widget', () => {
    render(<ChatWidget widgetKind="events" payload={{
      events: [{
        title: 'AI Engineering Meetup',
        sourceName: 'Luma',
        originalUrl: 'https://lu.ma/ai',
        eventDate: '2026-07-20T18:30:00Z',
      }],
    }} />)

    expect(screen.getByText('AI Engineering Meetup')).toBeInTheDocument()
    expect(screen.getByText('Luma')).toBeInTheDocument()
  })

  it('skips unknown widget kinds', () => {
    const { container } = render(<ChatWidget widgetKind="unknown" payload={{}} />)

    expect(container).toBeEmptyDOMElement()
  })
})
