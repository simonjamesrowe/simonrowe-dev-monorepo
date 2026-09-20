/**
 * Background sync utilities for offline-first functionality
 */

import {
  getOfflineQueue,
  removeFromOfflineQueue,
  incrementOfflineQueueRetries,
  addToOfflineQueue,
} from './db';

const MAX_RETRIES = 3;

/**
 * Process the offline queue when back online
 */
export async function processOfflineQueue(): Promise<void> {
  const queue = await getOfflineQueue();

  for (const item of queue) {
    try {
      // Process the queued action
      await processQueueItem(item);

      // Remove from queue on success
      await removeFromOfflineQueue(item.id);
    } catch (error) {
      console.error(`Failed to process queue item ${item.id}:`, error);

      // Increment retries
      if (item.retries < MAX_RETRIES) {
        await incrementOfflineQueueRetries(item.id);
      } else {
        // Remove after max retries
        console.warn(`Removing item ${item.id} after ${MAX_RETRIES} failed attempts`);
        await removeFromOfflineQueue(item.id);
      }
    }
  }
}

/**
 * Process a single queue item
 */
async function processQueueItem(item: {
  id: string;
  action: string;
  data: unknown;
  timestamp: number;
  retries: number;
}): Promise<void> {
  // Parse action type
  const [method, endpoint] = item.action.split(':');

  if (!method || !endpoint) {
    throw new Error(`Invalid action format: ${item.action}`);
  }

  // Make API request
  const response = await fetch(endpoint, {
    method: method.toUpperCase(),
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(item.data),
  });

  if (!response.ok) {
    throw new Error(`API request failed: ${response.statusText}`);
  }
}

/**
 * Queue an offline action
 */
export async function queueOfflineAction(
  method: string,
  endpoint: string,
  data: unknown,
): Promise<void> {
  const action = `${method}:${endpoint}`;
  await addToOfflineQueue(action, data);
}

/**
 * Initialize sync listeners
 */
export function initSync(): void {
  if (typeof window === 'undefined') {
    return;
  }

  // Process queue when coming back online
  window.addEventListener('online', () => {
    console.log('Back online - processing offline queue');
    processOfflineQueue().catch((error) => {
      console.error('Failed to process offline queue:', error);
    });
  });

  // Register background sync if supported
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  if ('serviceWorker' in navigator && 'sync' in (ServiceWorkerRegistration.prototype as any)) {
    navigator.serviceWorker.ready
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      .then((registration: any) => {
        if (registration.sync) {
          return registration.sync.register('sync-offline-queue');
        }
      })
      .catch((error) => {
        console.error('Background sync registration failed:', error);
      });
  }
}

/**
 * Check if online and queue is empty
 */
export async function isSynced(): Promise<boolean> {
  if (!navigator.onLine) {
    return false;
  }

  const queue = await getOfflineQueue();
  return queue.length === 0;
}
