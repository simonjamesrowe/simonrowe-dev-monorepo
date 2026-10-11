import { describe, expect, it } from 'vitest';

import {
  coparentFiles,
  coparentPrecacheScope,
  coparentWorkbox,
  scopePrecache,
} from '../../coparent-pwa.config';

const chunk = (
  fileName: string,
  overrides: Partial<{
    name: string;
    isEntry: boolean;
    imports: string[];
    dynamicImports: string[];
    css: string[];
    assets: string[];
  }> = {},
) => ({
  type: 'chunk' as const,
  fileName,
  name: overrides.name ?? fileName,
  isEntry: overrides.isEntry ?? false,
  imports: overrides.imports ?? [],
  dynamicImports: overrides.dynamicImports ?? [],
  viteMetadata: {
    importedCss: new Set(overrides.css ?? []),
    importedAssets: new Set(overrides.assets ?? []),
  },
});

const bundle = Object.fromEntries(
  [
    chunk('assets/coparent-a.js', {
      name: 'coparent',
      isEntry: true,
      imports: ['assets/react-b.js'],
      dynamicImports: ['assets/statements-c.js'],
      css: ['assets/styles-d.css'],
    }),
    chunk('assets/react-b.js'),
    chunk('assets/statements-c.js', { imports: ['assets/react-b.js'], assets: ['assets/logo-e.svg'] }),
    chunk('assets/main-f.js', { name: 'main', isEntry: true, dynamicImports: ['assets/z80-g.js'] }),
    chunk('assets/z80-g.js'),
  ].map((output) => [output.fileName, output]),
);

const entry = (url: string) => ({ url, revision: null, size: 1 });

describe('CoParent PWA precache', () => {
  it('never caches another frontend entry point as the root navigation', () => {
    expect(coparentWorkbox.globPatterns).toEqual([
      '**/*.{js,css,svg,woff,woff2}',
      'coparent/index.html',
    ]);
    expect(coparentWorkbox.navigateFallback).toBe('/coparent/index.html');
  });

  it('replaces a broken worker without relying on the CoParent UI', () => {
    expect(coparentWorkbox.skipWaiting).toBe(true);
    expect(coparentWorkbox.clientsClaim).toBe(true);
  });

  it('follows the CoParent entry through static and lazy imports, CSS and assets', () => {
    expect([...coparentFiles(bundle)].sort()).toEqual([
      'assets/coparent-a.js',
      'assets/logo-e.svg',
      'assets/react-b.js',
      'assets/statements-c.js',
      'assets/styles-d.css',
    ]);
  });

  it("leaves out the portfolio's chunks, which a parent never loads", () => {
    const files = coparentFiles(bundle);

    expect(files.has('assets/main-f.js')).toBe(false);
    expect(files.has('assets/z80-g.js')).toBe(false);
  });

  it('keeps the shell and CoParent files and drops everything else', () => {
    const kept = scopePrecache(
      [entry('coparent/index.html'), entry('assets/coparent-a.js'), entry('assets/z80-g.js')],
      coparentFiles(bundle),
    );

    expect(kept.map((item) => item.url)).toEqual(['coparent/index.html', 'assets/coparent-a.js']);
  });

  it('refuses to scope a build that has no CoParent entry', () => {
    expect(() => coparentFiles({ 'assets/main-f.js': bundle['assets/main-f.js'] })).toThrow(
      /no "coparent" entry/,
    );
  });

  it('fails the build rather than precache a worker without its shell', () => {
    const generateBundle = coparentPrecacheScope.generateBundle as unknown as (
      options: unknown,
      output: unknown,
    ) => void;
    generateBundle({}, bundle);
    const [transform] = coparentWorkbox.manifestTransforms;

    expect(() => transform([entry('assets/coparent-a.js')])).toThrow(/lost its shell/);
    expect(transform([entry('coparent/index.html'), entry('assets/z80-g.js')]).manifest).toEqual([
      entry('coparent/index.html'),
    ]);
  });
});
