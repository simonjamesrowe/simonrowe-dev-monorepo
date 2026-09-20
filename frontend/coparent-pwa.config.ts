export const coparentWorkbox = {
  // This service worker owns the whole CoParent origin. Never precache another HTML entry
  // point: Workbox treats / as index.html, which would make the portfolio shell win before
  // the CoParent navigation fallback. JavaScript and CSS stay broad because Rollup extracts
  // shared chunks with content-derived names that cannot be selected safely at config time.
  globPatterns: ['**/*.{js,css,svg,woff,woff2}', 'coparent/index.html'],
  navigateFallback: '/coparent/index.html',
  navigateFallbackDenylist: [/^\/api\//],
};
