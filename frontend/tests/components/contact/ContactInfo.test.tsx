import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ContactInfo } from '../../../src/components/contact/ContactInfo'

describe('ContactInfo', () => {
  const defaultProps = {
    location: 'London, UK',
    phoneNumber: '+44 7000 000000',
    primaryEmail: 'simon@simonjamesrowe.com',
    secondaryEmail: 'simon.rowe@gmail.com',
  }

  it('renders location text', () => {
    render(<ContactInfo {...defaultProps} />)
    expect(screen.getByText('London, UK')).toBeInTheDocument()
  })

  it('renders phone number with tel: href', () => {
    render(<ContactInfo {...defaultProps} />)
    const phoneLink = screen.getByText('+44 7000 000000')
    expect(phoneLink).toHaveAttribute('href', 'tel:+44 7000 000000')
  })

  it('renders primary email with mailto: href', () => {
    render(<ContactInfo {...defaultProps} />)
    const emailLink = screen.getByText('simon@simonjamesrowe.com')
    expect(emailLink).toHaveAttribute('href', 'mailto:simon@simonjamesrowe.com')
  })

  it('renders secondary email with mailto: href', () => {
    render(<ContactInfo {...defaultProps} />)
    const emailLink = screen.getByText('simon.rowe@gmail.com')
    expect(emailLink).toHaveAttribute('href', 'mailto:simon.rowe@gmail.com')
  })

  it('distinguishes between primary and secondary email labels', () => {
    render(<ContactInfo {...defaultProps} />)
    expect(screen.getByText('Primary:')).toBeInTheDocument()
    expect(screen.getByText('Secondary:')).toBeInTheDocument()
  })
})
