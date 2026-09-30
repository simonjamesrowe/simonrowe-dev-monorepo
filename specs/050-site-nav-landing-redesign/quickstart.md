# Quickstart: Site Navigation, Landing Hero and Portfolio

## Run the frontend against production data (read-only)

```bash
cd frontend
VITE_API_BASE_URL= VITE_PROXY_TARGET=https://api.simonrowe.dev npx vite --port 5199
```

`/api`, `/uploads` and `/ws` are proxied to production. Only GETs are made by the public pages. Chat goes to
the production assistant. Admin pages need a local backend: run `./scripts/start.sh` (see the `local-env`
skill).

## Check the header

1. At 1440px: open each of the four menus by hover, by click and by keyboard (Tab to a menu, then
   Enter/Space). Escape closes it.
2. Press `/` anywhere outside a field. The header search takes focus. Type "spring" and see grouped results.
3. At 390px: open the menu. Search comes first, then expand each group; the Ask button is pinned at the bottom.
4. Start the tour from About → "Take a tour" and confirm the search step types into the header search.

## Check the hero and its CMS (local backend)

1. Open `/admin/home-page` and change every field, then save.
2. Reload `/` and see each change. Reload the editor and see every field unchanged.
3. Enter `//evil.example` as a destination and confirm it is refused, naming the field.

## Check the portfolio (local backend)

1. `/admin/portfolio` shows the four seeded projects. Drag to reorder, and the header menu, home carousel and
   `/portfolio` follow.
2. Set one project to Live with an image and a live URL. Its card links to `/portfolio/{slug}`.
3. Unpublish it. It disappears from all three places, and `/portfolio/{slug}` shows not found.
