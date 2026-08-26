import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { SummaryButton } from '../../../src/components/news/SummaryButton'

describe('SummaryButton', () => {
  it('reads "Summarise" when no summary exists yet', () => {
    render(
      <SummaryButton articleTitle="Spring Boot 4" hasSummary={false} onClick={vi.fn()} />)

    expect(screen.getByRole('button', { name: /Generate an AI summary of Spring Boot 4/ }))
      .toHaveTextContent('Summarise')
  })

  it('reads "Read summary" when one already exists', () => {
    render(
      <SummaryButton articleTitle="Spring Boot 4" hasSummary onClick={vi.fn()} />)

    expect(screen.getByRole('button', {
      name: /Read the AI-generated summary of Spring Boot 4/,
    })).toHaveTextContent('Read summary')
  })

  it('reports the click', () => {
    const onClick = vi.fn()
    render(
      <SummaryButton articleTitle="Spring Boot 4" hasSummary={false} onClick={onClick} />)

    fireEvent.click(screen.getByRole('button'))

    expect(onClick).toHaveBeenCalledTimes(1)
  })

  /**
   * News cards are anchors wrapping the whole card. Without stopping the event, clicking
   * the button would navigate away to the original article instead of opening the drawer.
   */
  it('does not let the click reach the surrounding card link', () => {
    const onCardClick = vi.fn()
    const onClick = vi.fn()
    render(
      <a href="https://example.com" onClick={onCardClick}>
        <SummaryButton articleTitle="Spring Boot 4" hasSummary={false} onClick={onClick} />
      </a>,
    )

    fireEvent.click(screen.getByRole('button'))

    expect(onClick).toHaveBeenCalledTimes(1)
    expect(onCardClick).not.toHaveBeenCalled()
  })
})
