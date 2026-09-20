import { clearIdleActivity } from './idleActivity';

export function clearAuth0Cache() {
  const auth0KeyHints = ['@@auth0spajs@@', 'auth0', 'a0.spajs', 'a0-'];

  const shouldRemoveKey = (key: string) =>
    auth0KeyHints.some((hint) => key.toLowerCase().includes(hint.toLowerCase()));

  try {
    for (const key of Object.keys(localStorage)) {
      if (shouldRemoveKey(key)) localStorage.removeItem(key);
    }
  } catch {
    // ignore
  }

  try {
    for (const key of Object.keys(sessionStorage)) {
      if (shouldRemoveKey(key)) sessionStorage.removeItem(key);
    }
  } catch {
    // ignore
  }

  clearIdleActivity();
}
