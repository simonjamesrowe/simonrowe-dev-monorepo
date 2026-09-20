import '@testing-library/jest-dom';

import { afterAll, afterEach, beforeAll } from 'vitest';

import { server } from './mocks/server';

function createMemoryStorage(): Storage {
  const store = new Map<string, string>();

  return {
    get length() {
      return store.size;
    },
    clear: () => store.clear(),
    getItem: (key: string) => store.get(key) ?? null,
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    removeItem: (key: string) => {
      store.delete(key);
    },
    setItem: (key: string, value: string) => {
      store.set(key, value);
    },
  };
}

function ensureStorage(name: 'localStorage' | 'sessionStorage'): void {
  const storage = window[name] as Partial<Storage> | undefined;
  const hasStorageApi =
    typeof storage?.getItem === 'function' &&
    typeof storage?.setItem === 'function' &&
    typeof storage?.removeItem === 'function' &&
    typeof storage?.clear === 'function';

  if (!hasStorageApi) {
    Object.defineProperty(window, name, {
      configurable: true,
      value: createMemoryStorage(),
    });
  }
}

ensureStorage('localStorage');
ensureStorage('sessionStorage');

if (!HTMLElement.prototype.setPointerCapture) {
  HTMLElement.prototype.setPointerCapture = () => {};
}

if (!HTMLElement.prototype.releasePointerCapture) {
  HTMLElement.prototype.releasePointerCapture = () => {};
}

const originalGetComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = ((elt: Element, pseudoElt?: string | null) => {
  const styles = originalGetComputedStyle(elt, pseudoElt);

  return new Proxy(styles, {
    get(target, prop, receiver) {
      if (prop === 'transform' || prop === 'webkitTransform' || prop === 'mozTransform') {
        return Reflect.get(target, prop, receiver) || 'none';
      }

      return Reflect.get(target, prop, receiver);
    },
  });
}) as typeof window.getComputedStyle;

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});
