import { beforeEach, describe, expect, it } from 'vitest';

import { clearIdleActivity, IDLE_LAST_ACTIVITY_KEY } from './idleActivity';

describe('idle activity storage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('removes the previous authenticated session activity', () => {
    localStorage.setItem(IDLE_LAST_ACTIVITY_KEY, '123');

    clearIdleActivity();

    expect(localStorage.getItem(IDLE_LAST_ACTIVITY_KEY)).toBeNull();
  });
});
