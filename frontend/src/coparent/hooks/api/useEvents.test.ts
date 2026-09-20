import { describe, expect, it } from 'vitest';

import { serializeEventRequest } from './useEvents';

describe('event API serialization', () => {
  it('converts date-only form values to instants expected by the API', () => {
    expect(
      serializeEventRequest({
        startDate: '2026-09-20',
        endDate: '2026-09-21',
      }),
    ).toEqual({
      startDate: '2026-09-20T00:00:00Z',
      endDate: '2026-09-21T00:00:00Z',
    });
  });

  it('preserves API instants during edits', () => {
    expect(serializeEventRequest({ startDate: '2026-09-20T00:00:00Z' })).toEqual({
      startDate: '2026-09-20T00:00:00Z',
      endDate: undefined,
    });
  });
});
