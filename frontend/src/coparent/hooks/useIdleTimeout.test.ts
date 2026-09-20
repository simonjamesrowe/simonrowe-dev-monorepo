import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useIdleTimeout } from './useIdleTimeout';
import { IDLE_LAST_ACTIVITY_KEY } from '../lib/auth/idleActivity';

describe('useIdleTimeout', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    if (typeof localStorage.removeItem === 'function') {
      localStorage.removeItem(IDLE_LAST_ACTIVITY_KEY);
    }
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows warning during the warning window', () => {
    const onTimeout = vi.fn();

    const { result } = renderHook(() =>
      useIdleTimeout({
        timeout: 10_000,
        warningTime: 3_000,
        onTimeout,
      }),
    );

    act(() => {
      vi.advanceTimersByTime(8_000);
    });

    expect(result.current.showWarning).toBe(true);
    expect(result.current.remainingSeconds).toBeLessThanOrEqual(2);
    expect(onTimeout).not.toHaveBeenCalled();
  });

  it('calls onTimeout when timeout is reached', () => {
    const onTimeout = vi.fn();

    renderHook(() =>
      useIdleTimeout({
        timeout: 5_000,
        warningTime: 2_000,
        onTimeout,
      }),
    );

    act(() => {
      vi.advanceTimersByTime(5_100);
    });

    expect(onTimeout).toHaveBeenCalledTimes(1);
  });

  it('resets countdown on manual reset', () => {
    const onTimeout = vi.fn();

    const { result } = renderHook(() =>
      useIdleTimeout({
        timeout: 9_000,
        warningTime: 3_000,
        onTimeout,
      }),
    );

    act(() => {
      vi.advanceTimersByTime(8_000);
    });

    expect(result.current.showWarning).toBe(true);

    act(() => {
      result.current.resetTimer();
    });

    expect(result.current.showWarning).toBe(false);

    act(() => {
      vi.advanceTimersByTime(6_000);
    });

    expect(onTimeout).not.toHaveBeenCalled();
  });
});
