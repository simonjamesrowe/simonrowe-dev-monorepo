# Meals (`meals.simonrowe.dev`)

A private recipe, kitchen and shopping app for the site owner (spec `specs/053-meals/`). Every
route under `/api/meals/**` needs the `DEV_PORTAL_ADMIN` role, images included.

## The books are copyrighted: nothing about them goes in this repository

The recipes come from five purchased ebooks. The repository is public, so:

- no recipe text, photo or bundle is committed, and no test fixture is taken from a book
  (`MealBundles` in the tests is invented);
- the books reach production only as **bundles** uploaded by the owner;
- recipe photos live on the `meal-images` volume (`MEALS_IMAGE_PATH`), never under `uploads/`,
  which is served without authorisation.

## The app

`meals.simonrowe.dev` is a fourth Vite entry (`frontend/meals/`, `frontend/src/meals/`) in the
shared frontend image; `frontend/nginx.conf` picks `/meals/index.html` from the Host header, and
the edge proxy routes only `/api/meals/` to the backend from that hostname. It signs in with the
**main site's** Auth0 application (its token already carries `DEV_PORTAL_ADMIN`), keeping the
token in local storage like CoParent. Photos are fetched with the token as blobs, because an
`<img src>` cannot send one. Locally: `http://meals.localhost:5173` (Vite rewrites that host to
the meals entry; the backend's default CORS list includes it).

**A change to `config/nginx/nginx-proxy.conf` needs nginx recreated, not restarted** — the file
is a single-file bind mount, and the deploy's `restart` keeps the old one:
`docker compose -f docker-compose.prod.yml up -d --force-recreate --no-deps nginx`.

## Loading the books

A bundle is a zip with `manifest.json` (`version: 1`, `recipes`, `ingredients`, `templates`) and
the `photos/` it names. The bundles were built from the books while prototyping, outside the
repository, one per book to stay under the 10 MB upload limit.

Upload them on the app's **Import** page (several at once is fine), or with
`curl -F file=@meals-bundle-mmmb1.zip -H "Authorization: Bearer $TOKEN" .../api/meals/admin/bundles`.

Importing is insert-if-absent by id for recipes, ingredients and weeks: re-uploading adds
nothing and never overwrites an edit. A recipe that fails validation is skipped and listed in
the response's `skipped`; a broken zip or manifest is a 400. Upload all three bundles: the
weeks reference recipes across books.

## Backup and restore

All four collections (`meal_recipes`, `meal_ingredients`, `meal_plan_templates`,
`meal_ratings`) are in the full backup, and the photos travel as `meal-images/`.

- A restore replaces the collections and **adds** photos (files on disk are kept; a photo is
  named by the hash of its bytes, so a same-named file is the same photo).
- `RestoreService.ensureMealIndexes()` re-creates the ratings indexes, because Mongock never
  re-runs `V058CreateMeals`.
- `MealsBackupCoverageTest` finds every `@Document` in `com.simonrowe.meals` and fails if one
  is missing from the backup or the restore order, so a new collection cannot go unbacked.
- Known gap, shared with CoParent receipts: the safety snapshot taken just before a restore
  (`BackupService.createLocalBackup`) does not include the private directories. Harmless while
  restoring them only adds files.
