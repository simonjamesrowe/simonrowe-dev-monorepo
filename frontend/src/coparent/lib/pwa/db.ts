/**
 * IndexedDB utilities for offline data storage
 * Provides type-safe wrapper around idb for PWA offline functionality
 */

import type { DBSchema, IDBPDatabase } from 'idb';
import { openDB } from 'idb';

// Database schema definition
interface CoParentDB extends DBSchema {
  // Offline queue for actions to sync when back online
  'offline-queue': {
    key: string;
    value: {
      id: string;
      action: string;
      data: unknown;
      timestamp: number;
      retries: number;
    };
  };
  // Cache for API responses
  'api-cache': {
    key: string;
    value: {
      url: string;
      data: unknown;
      timestamp: number;
      expiresAt: number;
    };
  };
  // User preferences and settings
  preferences: {
    key: string;
    value: unknown;
  };
  // Draft messages and unsent data
  drafts: {
    key: string;
    value: {
      id: string;
      type: string;
      data: unknown;
      createdAt: number;
      updatedAt: number;
    };
  };
}

const DB_NAME = 'coparent-db';
const DB_VERSION = 1;

let dbInstance: IDBPDatabase<CoParentDB> | null = null;

/**
 * Initialize the IndexedDB database
 */
export async function initDB(): Promise<IDBPDatabase<CoParentDB>> {
  if (dbInstance) {
    return dbInstance;
  }

  dbInstance = await openDB<CoParentDB>(DB_NAME, DB_VERSION, {
    upgrade(db) {
      // Create offline queue store
      if (!db.objectStoreNames.contains('offline-queue')) {
        const offlineStore = db.createObjectStore('offline-queue', {
          keyPath: 'id',
        });
        // @ts-expect-error - idb type inference issue with createIndex
        offlineStore.createIndex('timestamp', 'timestamp');
      }

      // Create API cache store
      if (!db.objectStoreNames.contains('api-cache')) {
        const cacheStore = db.createObjectStore('api-cache', {
          keyPath: 'url',
        });
        // @ts-expect-error - idb type inference issue with createIndex
        cacheStore.createIndex('expiresAt', 'expiresAt');
      }

      // Create preferences store
      if (!db.objectStoreNames.contains('preferences')) {
        db.createObjectStore('preferences');
      }

      // Create drafts store
      if (!db.objectStoreNames.contains('drafts')) {
        const draftsStore = db.createObjectStore('drafts', {
          keyPath: 'id',
        });
        // @ts-expect-error - idb type inference issue with createIndex
        draftsStore.createIndex('type', 'type');
        // @ts-expect-error - idb type inference issue with createIndex
        draftsStore.createIndex('updatedAt', 'updatedAt');
      }
    },
  });

  return dbInstance;
}

/**
 * Get the database instance
 */
export async function getDB(): Promise<IDBPDatabase<CoParentDB>> {
  if (!dbInstance) {
    return initDB();
  }
  return dbInstance;
}

// =============================================================================
// Offline Queue Operations
// =============================================================================

export async function addToOfflineQueue(action: string, data: unknown): Promise<void> {
  const db = await getDB();
  const id = `${action}-${Date.now()}-${Math.random()}`;

  await db.add('offline-queue', {
    id,
    action,
    data,
    timestamp: Date.now(),
    retries: 0,
  });
}

export async function getOfflineQueue() {
  const db = await getDB();
  return db.getAll('offline-queue');
}

export async function removeFromOfflineQueue(id: string): Promise<void> {
  const db = await getDB();
  await db.delete('offline-queue', id);
}

export async function incrementOfflineQueueRetries(id: string): Promise<void> {
  const db = await getDB();
  const item = await db.get('offline-queue', id);
  if (item) {
    item.retries++;
    await db.put('offline-queue', item);
  }
}

export async function clearOfflineQueue(): Promise<void> {
  const db = await getDB();
  await db.clear('offline-queue');
}

// =============================================================================
// API Cache Operations
// =============================================================================

export async function cacheAPIResponse(
  url: string,
  data: unknown,
  ttlSeconds: number = 3600,
): Promise<void> {
  const db = await getDB();
  const now = Date.now();

  await db.put('api-cache', {
    url,
    data,
    timestamp: now,
    expiresAt: now + ttlSeconds * 1000,
  });
}

export async function getCachedAPIResponse(url: string): Promise<unknown | null> {
  const db = await getDB();
  const cached = await db.get('api-cache', url);

  if (!cached) {
    return null;
  }

  // Check if expired
  if (Date.now() > cached.expiresAt) {
    await db.delete('api-cache', url);
    return null;
  }

  return cached.data;
}

export async function clearExpiredCache(): Promise<void> {
  const db = await getDB();
  const all = await db.getAll('api-cache');
  const now = Date.now();

  for (const item of all) {
    if (now > item.expiresAt) {
      await db.delete('api-cache', item.url);
    }
  }
}

// =============================================================================
// Preferences Operations
// =============================================================================

export async function setPreference(key: string, value: unknown): Promise<void> {
  const db = await getDB();
  await db.put('preferences', value, key);
}

export async function getPreference<T = unknown>(key: string): Promise<T | null> {
  const db = await getDB();
  const value = await db.get('preferences', key);
  return (value as T) ?? null;
}

export async function removePreference(key: string): Promise<void> {
  const db = await getDB();
  await db.delete('preferences', key);
}

// =============================================================================
// Drafts Operations
// =============================================================================

export async function saveDraft(id: string, type: string, data: unknown): Promise<void> {
  const db = await getDB();
  const now = Date.now();

  const existing = await db.get('drafts', id);

  await db.put('drafts', {
    id,
    type,
    data,
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
  });
}

export async function getDraft(id: string) {
  const db = await getDB();
  return db.get('drafts', id);
}

export async function getAllDrafts(type?: string) {
  const db = await getDB();

  if (type) {
    // @ts-expect-error - idb type inference issue with index
    const index = db.transaction('drafts').store.index('type');
    // @ts-expect-error - idb type inference issue with getAll parameter
    return index.getAll(type);
  }

  return db.getAll('drafts');
}

export async function deleteDraft(id: string): Promise<void> {
  const db = await getDB();
  await db.delete('drafts', id);
}

export async function clearOldDrafts(daysOld: number = 30): Promise<void> {
  const db = await getDB();
  const cutoff = Date.now() - daysOld * 24 * 60 * 60 * 1000;
  const allDrafts = await db.getAll('drafts');

  for (const draft of allDrafts) {
    if (draft.updatedAt < cutoff) {
      await db.delete('drafts', draft.id);
    }
  }
}
