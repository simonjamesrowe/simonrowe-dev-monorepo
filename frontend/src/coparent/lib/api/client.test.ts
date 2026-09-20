import type { InternalAxiosRequestConfig } from 'axios';
import { describe, expect, it, vi } from 'vitest';

import { apiClient, setTokenGetter } from './client';

interface AdapterCapture {
  authorization?: string;
}

async function runWithCapturedAdapter(capture: AdapterCapture) {
  const originalAdapter = apiClient.defaults.adapter;

  apiClient.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    capture.authorization = (config.headers as Record<string, string> | undefined)?.Authorization;
    return {
      data: { ok: true },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    };
  };

  try {
    await apiClient.get('/test-endpoint');
  } finally {
    apiClient.defaults.adapter = originalAdapter;
  }
}

describe('api client interceptors', () => {
  it('adds Authorization header when token is available', async () => {
    setTokenGetter(async () => 'token-123');

    const capture: AdapterCapture = {};
    await runWithCapturedAdapter(capture);

    expect(capture.authorization).toBe('Bearer token-123');
  });

  it('handles missing token gracefully', async () => {
    setTokenGetter(async () => '');

    const capture: AdapterCapture = {};
    await runWithCapturedAdapter(capture);

    expect(capture.authorization).toBeUndefined();
  });

  it('continues request when token getter throws', async () => {
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    setTokenGetter(async () => {
      throw new Error('token error');
    });

    const capture: AdapterCapture = {};
    await runWithCapturedAdapter(capture);

    expect(capture.authorization).toBeUndefined();
    expect(consoleErrorSpy).toHaveBeenCalled();

    consoleErrorSpy.mockRestore();
  });
});
