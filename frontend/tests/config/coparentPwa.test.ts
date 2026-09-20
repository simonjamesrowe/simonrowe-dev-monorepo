import { describe, expect, it } from 'vitest';

import { coparentWorkbox } from '../../coparent-pwa.config';

describe('CoParent PWA precache', () => {
  it('never caches another frontend entry point as the root navigation', () => {
    expect(coparentWorkbox.globPatterns).toEqual([
      '**/*.{js,css,svg,woff,woff2}',
      'coparent/index.html',
    ]);
    expect(coparentWorkbox.navigateFallback).toBe('/coparent/index.html');
  });
});
