import type { Plugin } from 'vite';

/** The parts of a Rollup output chunk the precache scope reads. */
interface BundleChunk {
  type: 'chunk';
  fileName: string;
  name: string;
  isEntry: boolean;
  imports: string[];
  dynamicImports: string[];
  viteMetadata?: { importedCss: Set<string>; importedAssets: Set<string> };
}

interface BundleAsset {
  type: 'asset';
  fileName: string;
}

type Bundle = Record<string, BundleChunk | BundleAsset>;

interface PrecacheEntry {
  url: string;
  revision: string | null;
  size: number;
}

const COPARENT_ENTRY = 'coparent';
const COPARENT_SHELL = 'coparent/index.html';

/**
 * Every file the CoParent entry can load: its own chunk, every chunk it imports statically or
 * lazily, and the CSS and assets those chunks pull in. Walking the chunk graph is what lets the
 * precache follow Rollup's shared chunks, whose content-derived names cannot be chosen by a glob.
 */
export function coparentFiles(bundle: Bundle, entryName = COPARENT_ENTRY): Set<string> {
  const chunks = new Map(
    Object.values(bundle)
      .filter((output): output is BundleChunk => output.type === 'chunk')
      .map((chunk) => [chunk.fileName, chunk]),
  );
  const entry = [...chunks.values()].find((chunk) => chunk.isEntry && chunk.name === entryName);
  if (!entry) {
    throw new Error(`The build has no "${entryName}" entry chunk to scope the CoParent precache to.`);
  }

  const files = new Set<string>();
  const pending = [entry.fileName];
  while (pending.length > 0) {
    const fileName = pending.pop() as string;
    if (files.has(fileName)) continue;
    files.add(fileName);
    const chunk = chunks.get(fileName);
    if (!chunk) continue;
    chunk.viteMetadata?.importedCss.forEach((css) => files.add(css));
    chunk.viteMetadata?.importedAssets.forEach((asset) => files.add(asset));
    pending.push(...chunk.imports, ...chunk.dynamicImports);
  }
  return files;
}

/** The precache entries CoParent's worker keeps: its own files and its HTML shell. */
export function scopePrecache(entries: PrecacheEntry[], files: Set<string>): PrecacheEntry[] {
  return entries.filter((entry) => entry.url === COPARENT_SHELL || files.has(entry.url));
}

// Filled in by the plugin's generateBundle, which runs before the PWA plugin writes the worker
// in closeBundle. One build per process, so module state is enough.
let scopedFiles: Set<string> | null = null;

/** Records which built files belong to CoParent, for the worker's precache to keep. */
export const coparentPrecacheScope: Plugin = {
  name: 'coparent-precache-scope',
  apply: 'build',
  generateBundle(_options, bundle) {
    scopedFiles = coparentFiles(bundle as unknown as Bundle);
  },
};

export const coparentWorkbox = {
  // This service worker owns the whole CoParent origin. Never precache another HTML entry
  // point: Workbox treats / as index.html, which would make the portfolio shell win before
  // the CoParent navigation fallback. The globs find every built file; manifestTransforms
  // then keeps only what the CoParent entry loads. Precaching the whole dist made a new worker
  // download 265 files, most of them the portfolio's, before it could take over, so a deploy
  // took several page loads to reach a parent.
  globPatterns: ['**/*.{js,css,svg,woff,woff2}', COPARENT_SHELL],
  manifestTransforms: [
    (entries: PrecacheEntry[]) => {
      // Fail the build rather than ship a worker that precaches everything or nothing.
      if (!scopedFiles) {
        throw new Error('coparentPrecacheScope did not run, so the CoParent precache cannot be scoped.');
      }
      const manifest = scopePrecache(entries, scopedFiles);
      if (!manifest.some((entry) => entry.url === COPARENT_SHELL)) {
        throw new Error(`The CoParent precache lost its shell, ${COPARENT_SHELL}.`);
      }
      return { manifest, warnings: [] };
    },
  ],
  navigateFallback: '/coparent/index.html',
  navigateFallbackDenylist: [/^\/api\//],
  // A bad worker can render another app before CoParent's update prompt exists. Let a
  // corrected worker replace it without waiting for every affected tab to be closed.
  skipWaiting: true,
  clientsClaim: true,
};
