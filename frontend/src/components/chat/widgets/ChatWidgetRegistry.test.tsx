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

  it('skips unknown widget kinds', () => {
    const { container } = render(<ChatWidget widgetKind="unknown" payload={{}} />)

    expect(container).toBeEmptyDOMElement()
  })
})
