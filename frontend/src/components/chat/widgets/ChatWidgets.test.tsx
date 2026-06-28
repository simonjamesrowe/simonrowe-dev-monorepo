import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { BlogListWidget } from './BlogListWidget'
import { CodeExampleWidget } from './CodeExampleWidget'
import { EmploymentWidget } from './EmploymentWidget'
import { SkillsWidget } from './SkillsWidget'

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
    expect(screen.getByRole('link', { name: /read post/i })).toHaveAttribute(
      'href',
      '/blogs/streaming-chat',
    )
  })

  it('renders skills groups', () => {
    render(<SkillsWidget payload={{
      groups: [{ name: 'AI-native', skills: [{ name: 'Claude Code', rating: 8 }] }],
    }} />)

    expect(screen.getByText('AI-native')).toBeInTheDocument()
    expect(screen.getByText('Claude Code')).toBeInTheDocument()
    expect(screen.getByText('8/10')).toBeInTheDocument()
  })
})
