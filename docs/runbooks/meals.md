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

## Loading the books

A bundle is a zip with `manifest.json` (`version: 1`, `recipes`, `ingredients`, `templates`) and
the `photos/` it names. The bundles were built from the books while prototyping, outside the
repository, one per book to stay under the 10 MB upload limit.

    curl -F file=@meals-bundle-mmmb1.zip -H "Authorization: Bearer $TOKEN" \
      https://api.simonrowe.dev/api/meals/admin/bundles

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
