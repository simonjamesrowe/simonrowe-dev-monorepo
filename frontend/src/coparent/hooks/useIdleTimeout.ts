import { useCallback, useEffect, useRef, useState } from 'react';

import { IDLE_LAST_ACTIVITY_KEY } from '../lib/auth/idleActivity';

const DEFAULT_TIMEOUT = 10 * 60 * 1000;
const DEFAULT_WARNING_TIME = 60 * 1000;
const THROTTLE_MS = 1000;

export interface UseIdleTimeoutOptions {
  timeout?: number;
  warningTime?: number;
  onTimeout: () => void;
  enabled?: boolean;
}

export interface UseIdleTimeoutReturn {
  showWarning: boolean;
  remainingSeconds: number;
  resetTimer: () => void;
}

export function useIdleTimeout({
  timeout = DEFAULT_TIMEOUT,
  warningTime = DEFAULT_WARNING_TIME,
  onTimeout,
  enabled = true,
}: UseIdleTimeoutOptions): UseIdleTimeoutReturn {
  const [showWarning, setShowWarning] = useState(false);
  const [remainingSeconds, setRemainingSeconds] = useState(Math.ceil(timeout / 1000));

  const lastActivityRef = useRef<number>(Date.now());
  const lastEventRef = useRef<number>(0);
  const timerRef = useRef<number | null>(null);
  const timeoutTriggeredRef = useRef(false);
  const showWarningRef = useRef(false);

  const stopTimer = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const updateRemaining = useCallback(
    (now: number = Date.now()) => {
      const elapsed = now - lastActivityRef.current;
      const remainingMs = timeout - elapsed;
      const seconds = Math.max(0, Math.ceil(remainingMs / 1000));
      const shouldWarn = remainingMs > 0 && remainingMs <= warningTime;

      setRemainingSeconds(seconds);
      setShowWarning(shouldWarn);
      showWarningRef.current = shouldWarn;

      if (remainingMs <= 0 && !timeoutTriggeredRef.current) {
        timeoutTriggeredRef.current = true;
        setShowWarning(false);
        showWarningRef.current = false;
        stopTimer();
        onTimeout();
      }
    },
    [onTimeout, stopTimer, timeout, warningTime],
  );

  const setLastActivity = useCallback((timestamp: number) => {
    lastActivityRef.current = timestamp;
    timeoutTriggeredRef.current = false;
    localStorage.setItem(IDLE_LAST_ACTIVITY_KEY, String(timestamp));
  }, []);

  const resetTimer = useCallback(() => {
    if (!enabled) return;
    const now = Date.now();
    setShowWarning(false);
    showWarningRef.current = false;
    setLastActivity(now);
    updateRemaining(now);
  }, [enabled, setLastActivity, updateRemaining]);

  const handleActivity = useCallback(() => {
    if (!enabled || showWarningRef.current) return;
    const now = Date.now();
    if (now - lastEventRef.current < THROTTLE_MS) return;
    lastEventRef.current = now;
    setLastActivity(now);
    updateRemaining(now);
  }, [enabled, setLastActivity, updateRemaining]);

  useEffect(() => {
    if (!enabled) {
      stopTimer();
      setShowWarning(false);
      showWarningRef.current = false;
      setRemainingSeconds(Math.ceil(timeout / 1000));
      return;
    }

    const stored = localStorage.getItem(IDLE_LAST_ACTIVITY_KEY);
    const parsed = stored ? Number(stored) : Number.NaN;
    const now = Date.now();
    const initial = Number.isFinite(parsed) ? parsed : now;

    lastActivityRef.current = initial;
    timeoutTriggeredRef.current = false;

    updateRemaining(now);
    stopTimer();
    timerRef.current = window.setInterval(() => updateRemaining(), 1000);

    const passiveOptions: AddEventListenerOptions = { passive: true };
    const activeEvents = ['mousemove', 'keydown', 'click'];
    const passiveEvents = ['scroll', 'touchstart', 'wheel'];

    activeEvents.forEach((eventName) => {
      window.addEventListener(eventName, handleActivity);
    });

    passiveEvents.forEach((eventName) => {
      window.addEventListener(eventName, handleActivity, passiveOptions);
    });

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        updateRemaining();
      }
    };

    const handleStorage = (event: StorageEvent) => {
      if (event.key !== IDLE_LAST_ACTIVITY_KEY || !event.newValue) return;
      const value = Number(event.newValue);
      if (!Number.isFinite(value)) return;
      lastActivityRef.current = value;
      timeoutTriggeredRef.current = false;
      updateRemaining();
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    window.addEventListener('storage', handleStorage);

    return () => {
      stopTimer();
      activeEvents.forEach((eventName) => {
        window.removeEventListener(eventName, handleActivity);
      });
      passiveEvents.forEach((eventName) => {
        window.removeEventListener(eventName, handleActivity, passiveOptions);
      });
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      window.removeEventListener('storage', handleStorage);
    };
  }, [enabled, handleActivity, stopTimer, timeout, updateRemaining]);

  return { showWarning, remainingSeconds, resetTimer };
}
