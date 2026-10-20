import { describe, expect, it } from 'vitest'

import { parseTicketReference } from '../src/services/softwareFactoryApi'

describe('parseTicketReference', () => {
  it('reads an identifier in any case', () => {
    expect(parseTicketReference(' sim-107 ')).toBe('SIM-107')
    expect(parseTicketReference('SIM-1')).toBe('SIM-1')
  })

  it('reads the identifier out of a Linear issue URL', () => {
    expect(parseTicketReference('https://linear.app/simonrowedev/issue/SIM-107/error-in-backend'))
      .toBe('SIM-107')
  })

  it('refuses lookalike hosts, other schemes and anything that is not a ticket', () => {
    for (const value of [
      '',
      'nonsense',
      'SIM',
      'SIM-1x',
      'https://linear.app.attacker.example/x/issue/SIM-1',
      'http://linear.app/x/issue/SIM-1',
      'https://linear.app/x/project/SIM-1',
      'https://linear.app/x/issue',
      'not a url/at all',
    ]) {
      expect(parseTicketReference(value), value).toBeNull()
    }
  })
})
