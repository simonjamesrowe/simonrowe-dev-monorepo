export const IDLE_LAST_ACTIVITY_KEY = 'idleTimeout:lastActivity';

export function clearIdleActivity() {
  try {
    localStorage.removeItem(IDLE_LAST_ACTIVITY_KEY);
  } catch {
    // Storage can be unavailable in privacy-restricted browser contexts.
  }
}
