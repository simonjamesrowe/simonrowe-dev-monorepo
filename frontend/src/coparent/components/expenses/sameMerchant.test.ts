import { describe, expect, it } from 'vitest';

import { merchantKey, sameMerchant, tickedByDefault, type SameMerchantCandidate } from './sameMerchant';

const candidate = (fingerprint: string, description: string, overrides: Partial<SameMerchantCandidate> = {}) => ({
  fingerprint,
  uploadId: 'up-1',
  transaction: null,
  description,
  details: null,
  date: '2026-10-01',
  amountPence: 1250,
  account: 'Santander current account ··3741',
  state: null,
  match: null,
  ...overrides,
});

describe('merchantKey', () => {
  it('ignores store numbers, references, punctuation and case', () => {
    expect(merchantKey('BOOTS 0775')).toBe('boots');
    expect(merchantKey('Boots 1234')).toBe('boots');
    expect(merchantKey('AMZNMktplace*AB12CD')).toBe('amznmktplace');
    expect(merchantKey('Kings College Ho')).toBe(merchantKey('KINGS COLLEGE HO'));
  });

  it('keeps a description that is nothing but numbers whole', () => {
    expect(merchantKey(' 0775 ')).toBe('0775');
  });
});

describe('sameMerchant', () => {
  it('finds the other payments to the merchant, newest first, once each', () => {
    const found = sameMerchant({ fingerprint: 'a', description: 'BOOTS 0775' }, [
      candidate('a', 'BOOTS 0775'),
      candidate('b', 'BOOTS 1234', { date: '2026-09-01' }),
      candidate('c', 'Boots 0775', { date: '2026-10-05' }),
      candidate('b', 'BOOTS 1234', { transaction: null, state: 'suggested' }),
      candidate('d', 'BOOTS 0775', { state: 'logged' }),
      candidate('e', 'TESCO STORES 2041'),
    ]);
    expect(found.map((entry) => entry.fingerprint)).toEqual(['c', 'b']);
    // The row of an upload is kept over the same transaction listed as a suggestion.
    expect(found[1].state).toBeNull();
  });
});

describe('tickedByDefault', () => {
  it('leaves out what was marked not shared, or is already some other expense', () => {
    expect(tickedByDefault(candidate('a', 'X'), 'new')).toBe(true);
    expect(tickedByDefault(candidate('a', 'X', { state: 'dismissed' }), 'new')).toBe(false);
    const match = { id: 'old', title: 'Prescription', date: '2026-10-01' };
    expect(tickedByDefault(candidate('a', 'X', { match }), 'new')).toBe(false);
    // Looking like the expense just logged is expected of a repeat payment.
    expect(tickedByDefault(candidate('a', 'X', { match: { ...match, id: 'new' } }), 'new')).toBe(true);
  });
});
