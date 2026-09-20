/**
 * Hook to manage service worker updates
 */

import { useState, useEffect } from 'react';
import { Workbox } from 'workbox-window';

export function useServiceWorker() {
  const [wb, setWb] = useState<Workbox | null>(null);
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [isRegistering, setIsRegistering] = useState(false);

  useEffect(() => {
    if (
      typeof window === 'undefined' ||
      !('serviceWorker' in navigator) ||
      window.location.origin !== import.meta.env.VITE_COPARENT_CANONICAL_ORIGIN ||
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (import.meta as any).env?.DEV
    ) {
      return;
    }

    const workbox = new Workbox('/coparent-sw.js');

    workbox.addEventListener('installed', (event) => {
      if (event.isUpdate) {
        setUpdateAvailable(true);
      }
    });

    workbox.addEventListener('waiting', () => {
      setUpdateAvailable(true);
    });

    workbox.addEventListener('controlling', () => {
      window.location.reload();
    });

    setWb(workbox);
    setIsRegistering(true);

    workbox
      .register()
      .then(() => {
        setIsRegistering(false);
      })
      .catch((error) => {
        console.error('Service worker registration failed:', error);
        setIsRegistering(false);
      });
  }, []);

  const skipWaiting = () => {
    if (wb) {
      wb.messageSkipWaiting();
    }
  };

  return {
    updateAvailable,
    isRegistering,
    skipWaiting,
  };
}
