import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ContactDetails } from '../../src/components/profile/ContactDetails'

describe('ContactDetails', () => {
  it('starts collapsed and toggles contact content', () => {
    render(
      <ContactDetails
        location="London"
        phoneNumber="+440000"
        primaryEmail="primary@example.com"
        secondaryEmail="secondary@example.com"
      />,
    )

    expect(screen.queryByText('London')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Show details' }))
    expect(screen.getByText('London')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'primary@example.com' })).toHaveAttribute(
      'href',
      'mailto:primary@example.com',
    )

    fireEvent.click(screen.getByRole('button', { name: 'Hide details' }))
    expect(screen.queryByText('London')).not.toBeInTheDocument()
  })
})
