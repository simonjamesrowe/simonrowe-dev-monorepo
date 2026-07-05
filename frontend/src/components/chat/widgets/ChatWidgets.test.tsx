import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { BlogListWidget } from './BlogListWidget'
import { CodeExampleWidget } from './CodeExampleWidget'
import { EmploymentWidget } from './EmploymentWidget'
import { EventsWidget } from './EventsWidget'
import { NewsWidget } from './NewsWidget'
import { SkillsWidget } from './SkillsWidget'
import { resolveChatWidgetImageUrl } from './chatWidgetImages'

describe('chat widgets', () => {
  it('renders employment entries', () => {
    render(<EmploymentWidget payload={{
      jobs: [{
        company: 'Global',
        title: 'Head of Engineering',
        start: '2021',
        end: 'Present',
        summary: 'Leads commercial trading engineering.',
        skills: ['Kafka'],
      }],
    }} />)

    expect(screen.getByText('Head of Engineering')).toBeInTheDocument()
    expect(screen.getByText('Global')).toBeInTheDocument()
    expect(screen.getByText('2021 - Present')).toBeInTheDocument()
    expect(screen.getByText('Kafka')).toBeInTheDocument()
  })

  it('renders code examples', () => {
    render(<CodeExampleWidget payload={{
      examples: [{
        title: 'Outbox pattern',
        description: 'Transactional messaging',
        language: 'java',
        code: '```java\nclass Outbox {}\n```',
        skills: ['Spring Boot'],
      }],
    }} />)

    expect(screen.getByText('Outbox pattern')).toBeInTheDocument()
    expect(screen.getByText('Transactional messaging')).toBeInTheDocument()
    expect(screen.queryByText('java')).not.toBeInTheDocument()
    expect(screen.getByText('Spring Boot')).toBeInTheDocument()
    expect(screen.getByText('class Outbox {}')).toBeInTheDocument()
    expect(screen.queryByText(/```java/)).not.toBeInTheDocument()
  })

  it('renders blog posts', () => {
    render(<BlogListWidget payload={{
      posts: [{
        title: 'Streaming chat',
        summary: 'Why visible progress matters',
        publishedDate: '2026-05-01T00:00:00Z',
        tags: ['AI'],
        url: '/blogs/streaming-chat',
      }],
    }} />)

    expect(screen.getByText('Streaming chat')).toBeInTheDocument()
    expect(screen.getByText('Why visible progress matters')).toBeInTheDocument()
    expect(screen.getByText('AI')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Read post: Streaming chat' })).toHaveAttribute(
      'href',
      '/blogs/streaming-chat',
    )
  })

  it('renders blog image when present', () => {
    render(<BlogListWidget payload={{
      posts: [{
        title: 'Streaming chat',
        summary: 'Why visible progress matters',
        imageUrl: '/uploads/blog-1/small.webp',
        publishedDate: '2026-05-01T00:00:00Z',
        tags: ['AI'],
        url: '/blogs/streaming-chat',
      }],
    }} />)

    const image = document.querySelector('.chat-widget__image')
    expect(image).toHaveAttribute('alt', '')
    expect(image).toHaveAttribute('src', expect.stringContaining('/uploads/blog-1/small.webp'))
  })

  it('does not render an empty blog image slot when image is missing', () => {
    const { container } = render(<BlogListWidget payload={{
      posts: [{
        title: 'Streaming chat',
        summary: 'Why visible progress matters',
        publishedDate: '2026-05-01T00:00:00Z',
        tags: ['AI'],
        url: '/blogs/streaming-chat',
      }],
    }} />)

    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(container.querySelector('.chat-widget__media')).toBeNull()
  })

  it('renders news cards with optional images and source links', () => {
    render(<NewsWidget payload={{
      articles: [{
        title: 'Spring AI adds new advisor APIs',
        summary: 'Advisor APIs improve RAG composition.',
        sourceName: 'Spring Blog',
        originalUrl: 'https://spring.io/blog/advisors',
        publishedDate: '2026-07-01T09:00:00Z',
        imageUrl: 'https://example.com/spring.png',
      }],
    }} />)

    expect(screen.getByText('Spring AI adds new advisor APIs')).toBeInTheDocument()
    expect(screen.getByText('Spring Blog')).toBeInTheDocument()
    const image = document.querySelector('.chat-widget__image')
    expect(image).toHaveAttribute('alt', '')
    expect(image).toHaveAttribute('src', 'https://example.com/spring.png')
    expect(screen.getByRole('link', { name: 'Read source: Spring AI adds new advisor APIs' })).toHaveAttribute(
      'href',
      'https://spring.io/blog/advisors',
    )
  })

  it('renders news cards without image slots when image is missing', () => {
    const { container } = render(<NewsWidget payload={{
      articles: [{
        title: 'No Image Article',
        summary: 'Text-only card.',
        sourceName: 'InfoQ',
        originalUrl: 'https://infoq.com/no-image',
      }],
    }} />)

    expect(screen.getByText('No Image Article')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(container.querySelector('.chat-widget__media')).toBeNull()
  })

  it('renders event cards with date venue and external link', () => {
    const eventDate = '2026-07-20T18:30:00Z'
    const eventEndDate = '2026-07-20T20:00:00Z'

    render(<EventsWidget payload={{
      events: [{
        title: 'London Java Meetup',
        summary: 'Talks on production Java.',
        sourceName: 'Luma',
        originalUrl: 'https://lu.ma/java',
        eventDate,
        eventEndDate,
        venue: 'CodeNode',
        location: 'London',
      }],
    }} />)

    const expectedDate = new Date(eventDate).toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
    const expectedEndDate = new Date(eventEndDate).toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })

    expect(screen.getByText('London Java Meetup')).toBeInTheDocument()
    expect(screen.getByText('Luma')).toBeInTheDocument()
    expect(screen.getByText(new RegExp(expectedDate.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))))
      .toBeInTheDocument()
    expect(screen.getByText(new RegExp(expectedEndDate.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))))
      .toBeInTheDocument()
    expect(screen.getByText(/CodeNode, London/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View event: London Java Meetup' })).toHaveAttribute(
      'href',
      'https://lu.ma/java',
    )
  })

  it('renders event thumbnails as decorative images', () => {
    render(<EventsWidget payload={{
      events: [{
        title: 'London Java Meetup',
        imageUrl: 'https://example.com/event.png',
      }],
    }} />)

    const image = document.querySelector('.chat-widget__image')
    expect(image).toHaveAttribute('alt', '')
    expect(image).toHaveAttribute('src', 'https://example.com/event.png')
  })

  it('renders skills groups', () => {
    render(<SkillsWidget payload={{
      groups: [{ name: 'AI-native', skills: [{ name: 'Claude Code', rating: 8 }] }],
    }} />)

    expect(screen.getByText('AI-native')).toBeInTheDocument()
    expect(screen.getByText('Claude Code')).toBeInTheDocument()
    expect(screen.getByText('8/10')).toBeInTheDocument()
  })

  it('resolves accepted chat widget image URLs and rejects unsafe relative paths', () => {
    expect(resolveChatWidgetImageUrl('/uploads/blog-1/small.webp'))
      .toEqual(expect.stringContaining('/uploads/blog-1/small.webp'))
    expect(resolveChatWidgetImageUrl('http://example.com/image.png')).toBe('http://example.com/image.png')
    expect(resolveChatWidgetImageUrl('https://example.com/image.png')).toBe('https://example.com/image.png')
    expect(resolveChatWidgetImageUrl('/images/image.png')).toBeUndefined()
    expect(resolveChatWidgetImageUrl('images/image.png')).toBeUndefined()
  })
})
