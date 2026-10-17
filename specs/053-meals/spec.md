# 053 · Meals

A private app at `meals.simonrowe.dev` for one person. It holds the recipes from Dave Fell's
five ebooks plus any recipe imported from a link or a photo. It keeps an inventory of the
ingredients in the house, suggests what to eat from it, and turns a week's plan into a list
to work through while filling an online basket. Claude Code reaches all of it through a private MCP
server.

Status: **being built** as five pull requests; see `plan.md`, which also records where the
build departs from this design (bundle import, Up next instead of a week grid). Settled in a
brainstorm on 2026-10-16.

## Decisions so far

| Question | Decision |
| --- | --- |
| Who uses it | Simon only. Personal records (ratings, inventory, Up next, tokens) carry an owner, so a second person later needs no migration. The library itself is shared content and has none. |
| Prices | **Ignored everywhere.** The books' per-ingredient and per-serving costs are not imported. |
| Inventory | Yes, and it drives suggestions and the shopping list. |
| Cooking pattern | Not all batch prep. Some meals are cooked fresh, some prepped, and a cook may or may not cover several days. |
| Logging | Plan only. The one thing recorded after eating is a **rating**. |
| Calories | **Not tracked.** No daily target, no day totals, no diary. A recipe still shows its macros because the book prints them. |
| Shopping | Online, at **Ocado, Amazon or Deliveroo**. The list is a basket-building checklist, not tied to any one shop. |
| Recipes from elsewhere | Yes, from a link or a photo. |
| Recipe photos | Yes, imported with each recipe, kept privately. |
| Backup and restore | Everything the app holds, collections and photos, is in the nightly backup and comes back on restore. See "Backup and restore". |
| MCP | Yes, for **Claude Code**. A personal token is enough; there is no OAuth connector for claude.ai. |
| CoParent | Not linked. |
| Getting the books in | Upload the PDFs on an admin screen. |

## The constraint everything else follows: the books are copyrighted

Every ebook says "All rights reserved", and this repository is public. So:

- **No book content is ever committed.** That rules out a seed directory (unlike portfolio
  pages, V049/V050), real recipe fixtures, and real pages in tests. Test fixtures are synthetic
  recipes in the books' layout, the same rule as bank statements in 052.
- The PDFs are uploaded at runtime, and the recipes live only in Mongo and in the private
  backup.
- **Every route requires authentication.** That covers the API, the images and the MCP server.
  There is no anonymous tier, unlike Term Time.
- Recipe photos never go in `uploads/`, which is served without authorisation. They follow
  `CoparentReceiptStore`:
  - a `MealImageStore` writes under `meals.image-path`, which in production is
    `/workspace/meal-images/` on a new `meal-images` volume
  - a persistence test pins the path and volume, like `CoparentReceiptPersistenceTest`
  - the type is sniffed from the bytes (JPEG, PNG or WebP), never taken from a name
  - images are served by an authenticated controller with `nosniff`

## Hosting

The app follows CoParent's layout:

- **Frontend:** a third Vite entry, `frontend/meals/`. It is a PWA so it installs on the phone.
- **Backend:** package `com.simonrowe.meals`, under `/api/meals/**`, which requires the
  **admin role** in `SecurityConfig`. A single user needs no allowlist of its own.
- **nginx:** a `meals.simonrowe.dev` server block that sends `location = /` to `/meals/` and
  `/api/meals/` to the backend, as Term Time and CoParent do.
- **Configuration:**
  - `CORS_ALLOWED_ORIGINS` must list the hostname even though calls are same-origin. Term Time
    learned this: Spring checks the `Origin` header regardless of who proxies it.
  - Auth0 needs the hostname added as an allowed callback and web origin.
- **Mongo:** collections in the main database, prefixed `meal_`. Indexes come from Mongock
  change units, because `auto-index-creation` is off. Backup and restore are covered in their
  own section below.

## Model

### Ingredients: the entity everything hangs off

The inventory, the shopping list, "what can I cook now" and the coverage of imported recipes
all need one answer to "is this the same thing?". "1 Scoop (25g) Protein Powder",
"protein powder" and "30g whey" are one **ingredient**.

- `meal_ingredients` holds a canonical name, aliases, a default unit (g, ml or each), an
  optional grams-per-unit ("1 egg = 50g", "1 scoop = 25g"), an aisle, and a **tracking tier**
  (below).
- Recipe lines keep the book's own text for display. They also carry a parsed
  `(ingredientId, quantity, unit)` for maths.
- New names are matched by the model against existing ingredients when a recipe is imported,
  and you confirm the match. A wrong merge corrupts every list that uses it, so new ingredients
  are proposed, never created silently.

### Recipes

`meal_recipes` holds:

- name, source (`BOOK` with book and page, `LINK` with URL, `PHOTO`), and category (breakfast,
  main, one dish, slow cooker, quick, snack)
- vegetarian or vegan flag, servings
- calories, protein, carbs and fat **per serving**, plus `macrosEstimated` (true when the model
  worked them out rather than the source stating them)
- ingredient lines, method steps, tips and swaps
- storage flags (fridge days, freezable)
- photo, and the MyFitnessPal barcode image when the book has one

### Ratings

`meal_ratings` gets one row per time you ate something: recipe, date, a 1–5 score, "would make
again", tags (spicy, filling, faff…) and notes ("halve the Biscoff"). This is the only log.

### Inventory

`meal_inventory` holds one row per ingredient in the house. It has two tiers, because exact
grams for everything would be wrong within a week:

| Tier | For | Holds |
| --- | --- | --- |
| `TRACKED` | fresh food, meat and fish, dairy, bread | quantity and use-by date |
| `STAPLE` | oats, spices, sauces, protein powder, tins | have / low / out |

Every change is an entry in `meal_inventory_events` (what changed, why and when), so a wrong
deduction can be found and reverted.

### Plans

`meal_plans` holds one row per week. Each day has slots (breakfast, lunch, dinner, snack), and
each slot holds a recipe and a portion count, or is marked **free** (eating out, ready meal).
A **cook** can fill several slots, which models the leftovers that make prep work. It is never
assumed: a plan of fresh cooking is just as valid.

Dave's meal plans are imported as **templates**: a week of recipes he put together. Each is
built for a calorie level (1300–2700), which is kept only as a label on the template. Nothing
in the app uses it. "Start from this week" copies one into a plan for you to change.

## Getting the books in

> **Superseded for the five books** by a bundle import; see `plan.md`. The flow below is
> kept for a future book and is what the step-5 draft review screen builds on.

1. Upload a PDF on the admin screen. It is stored privately until the import finishes, then
   deleted.
2. Pages are rendered to images with PDFBox, which is already a dependency through Term Time.
   **Read the images, not the extracted text.** The text layer separates quantities from
   ingredients and breaks headings across lines (`BAKEWELl / OVERNIGHT / Oats`).
3. A recipe spans two pages: a photo with macros, then method and ingredients. The model gets
   both and returns the recipe structure as structured output (`response_format` with a JSON
   schema), as 052 does.
4. Free checks before anything is shown to you:
   - 4×protein + 4×carbs + 9×fat is within about 10% of the stated calories. The app tracks no
     calories, but a mismatch is the cheapest sign that the model misread a page.
   - there is at least one ingredient line and one method step
   - the name matches the book's contents page
5. **Review screen:** each recipe is shown beside its page images. You approve it, fix it or
   skip it, and approve or merge new ingredients here. Nothing reaches the library unreviewed.
6. Meal-plan books go through the same flow into templates. Their grocery lists are not
   imported: the shopping list is always worked out from the recipes.

Importing again is safe: a recipe is keyed on book and page, and an approved recipe is never
overwritten.

## Recipes from elsewhere

- **Link:** fetched through the existing guarded fetcher (SSRF check, every redirect
  re-validated). Pages with schema.org `Recipe` JSON-LD are read deterministically. Others go
  to the model.
- **Photo:** a cookbook page or a screenshot. The browser fixes orientation and scales the
  image, exactly as on Term Time's notes screen.
- Both produce the same draft and the same review screen as the books. Missing macros are
  estimated and shown as estimated.

## Keeping the inventory true

An inventory nobody maintains is worse than none, so most updates happen as a side effect:

1. **The online order arrives.** Paste the order confirmation, or a screenshot of the order
   page, for Ocado, Amazon or Deliveroo. The model reads it into proposed additions, and you
   confirm. The three shops' emails differ: Ocado's lists every item with substitutions and
   missing items, while Amazon's and Deliveroo's often do not, so a screenshot of the order page
   is the fallback. The parser is told which shop the order came from.
2. **"Cooked it"** takes the recipe's ingredients off, scaled to portions. The confirmation
   shows the deductions so you can adjust them ("used half the pack, not all"). This is the
   same moment you are prompted to rate the recipe.
3. **Quick add in plain words:** "used the last of the rice, 6 eggs left". The model proposes
   changes and you confirm.
4. **Photo** of a fridge shelf or cupboard gives proposed changes, and you confirm. This works
   less well than the others, so it is the last to build.

Items with a use-by date in the next 2 days are flagged on the home screen.

## Suggestions: "what should I eat?"

This is deterministic scoring first, with the model second. The candidate set is small
(about 150 recipes plus imports), so there is no vector store. That also avoids the trap
Term Time hit, where a second Elasticsearch `VectorStore` bean removes the main one.

Score per recipe:

- **coverage:** the share of its ingredients in the inventory, weighted towards the main ones,
  plus a list of what's missing
- **urgency:** it uses something near its use-by date
- **liking:** your ratings, and for unrated recipes the ratings of similar ones (same category
  and main protein)
- **variety:** you haven't eaten it recently

Before you have rated anything, suggestions lean on coverage and Dave's templates (which
recipes he pairs together).

The model sits on top for questions the scores cannot answer ("something spicy, not pasta,
using the mince"). It works through the same tools as the MCP server.

## Shopping list

Plan for the week, minus inventory, gives what to buy:

- quantities summed per ingredient across every cook, scaled by portions
- `STAPLE` items appear only when marked low or out
- **a basket-building checklist:** one search-friendly line per item ("chicken breast 1.2kg",
  "tortilla wraps × 9"), grouped by aisle. Tap an item once it is in the basket. The search term
  is short, because it is what you type into the shop's search box.
- **one list, any shop.** Ocado, Amazon and Deliveroo each fit a different order (a weekly shop,
  a few staples, a quick top-up), so the list can be split, e.g. tick five things for a
  Deliveroo top-up and leave the rest for Ocado. Items not yet ordered stay on the list.
- copy as text, for pasting into a note or a chat
- after the order is placed, the delivery is reconciled through inventory input 1

There are no prices and no basket automation, since none of the three shops has a public API
for adding to a basket. The design depends on no shop-specific list-paste feature either.

## MCP server for Claude Code

This is **a second, private MCP server at `/api/meals/mcp`**, separate from the public `/mcp`.

- **The meals tools must not be a `ToolCallbackProvider` bean.** Spring AI's MCP
  auto-configuration publishes *every* such bean on the public `/mcp` (that is how
  `McpServerConfig` exposes the profile tools). A meals provider declared the same way would
  serve copyrighted recipes and the personal inventory to anyone.
  - The private server is built by hand from the MCP Java SDK's WebMVC streamable transport.
    Verify the exact transport class against Spring AI 2.0 before building.
  - A test calls `tools/list` on the public `/mcp` and asserts that no meals tool appears.
- **Authentication is a personal access token, not an Auth0 JWT.** An Auth0 token expires
  within a day, which a line in Claude Code's config cannot follow.
  - The app's settings screen mints the token, shows it once, and stores only its SHA-256.
  - The token can be revoked, and it is accepted only on `/api/meals/mcp`.
  - Setup command:
    `claude mcp add --transport http meals https://meals.simonrowe.dev/api/meals/mcp --header "Authorization: Bearer <token>"`.
- **Tools.** Each is one service method, shared with the in-app chat.
  - Read: `searchRecipes`, `getRecipe`, `suggestMeals`, `whatCanICookNow`, `getInventory`,
    `getPlan`, `getShoppingList`
  - Write: `updateInventory`, `planMeal`, `rateMeal`, `importRecipeFromUrl`
  - Write tools change only meals data. Inventory changes from Claude go through the same event
    log, so they can be reverted.
- **Prompts:** `plan-my-week` and `use-up-whats-expiring`.

## Backup and restore

Almost nothing here can be re-derived. Ratings, inventory, plans and imported recipes exist
only in this app. The book recipes could be imported again, but the source PDF is deleted
after import and every fix and ingredient merge you made would be lost. So all of it goes in
the existing full backup (Google Drive, with media, newest 7 kept). Nothing goes in a separate
mechanism.

**What is backed up**

| Data | How |
| --- | --- |
| `meal_recipes`, `meal_ingredients`, `meal_ratings`, `meal_inventory`, `meal_inventory_events`, `meal_plans`, `meal_plan_templates`, `meal_mcp_tokens` | Added to `BackupService.BACKUP_COLLECTIONS` and `RestoreService`'s import order. They are in the main database, so no new database block is needed. |
| Recipe photos and MyFitnessPal barcodes | The `meal-images` directory is exported under `meal-images/` with the existing `exportDirectory`, and restored with `restoreDirectoryEntries`, which carries the zip-slip guard. |
| Indexes | A restore drops each collection, and Mongock never re-runs a recorded change unit. So `RestoreService.ensureMealIndexes()` calls the change unit's `createIndexes` directly, as `ensureCoparentIndexes()` does. The unique indexes carry real invariants: book and page per recipe, one inventory row per ingredient, one token hash. |

`meal_mcp_tokens` holds only SHA-256 hashes. It is backed up so that a restore does not break
the token already in Claude Code's config.

**Deliberately not backed up:** an import in progress, meaning a staged PDF and its
not-yet-approved drafts. The PDF is large and copyrighted, and it is transient. After a
restore, upload the book again; approved recipes are skipped by their book-and-page key.

**Restore semantics.** Collections are replaced, as for every other collection. Photos are
added: files already on disk are kept, as for CoParent receipts. So a photo can never be lost
to a restore, and one whose recipe no longer exists after the restore is harmless.

**Tests, in the step that adds each collection, not at the end**

- `MealsBackupCoverageTest`, like `CoparentBackupCoverageTest`, asserts that every
  `meal_`-prefixed collection the app writes is in the backup list, the restore order and
  `ensureMealIndexes()`. It discovers the collections from the `@Document` classes, so a new
  collection added later fails the build instead of quietly going unbacked.
- A round-trip test backs up a populated meals dataset (synthetic recipes, never book
  content) into a zip, wipes it, restores it, and asserts:
  - every document comes back unchanged
  - every photo comes back byte for byte
  - every unique index exists again and still rejects a duplicate
- The production path is checked once by hand with the `prod-data-restore` skill against a
  local stack, in the step that first ships data.

**Found while designing this (pre-existing, not in this scope):** `BackupService.createLocalBackup()`,
the snapshot taken just before a restore, exports collections and `uploads/` but not the
private directories (`school-attachments/`, `coparent-receipts/`). That is harmless today,
because restoring those directories only adds files. Raise it as a separate fix. Meal photos
will inherit the same behaviour.

## Build order

1. **Library:** upload the books, review the import, standard ingredients, recipe photos,
   browse and filter, recipe page with cook mode, and ratings. This step also adds the backup
   coverage test, the round-trip test and the `meal-images` volume.
2. **Inventory:** the two tiers, quick add, "cooked it" deductions, use-by flags, and "what can
   I cook now".
3. **Planner and shopping list:** templates from Dave's plans, cooks covering several slots,
   and the basket-building checklist.
4. **MCP server and in-app chat:** token minting, tools and prompts.
5. **Recipes from elsewhere:** link, then photo.
6. Reading a delivery email and fridge photos into inventory changes.

Each step is useful without the next. Each step adds its own collections to the backup; the
coverage test fails the build otherwise.

## Open questions

1. **How much planning?** Settled: a meal not cooked from a recipe is a **free** slot. Still
   open: whether a day-by-day week is wanted at all, or only a shortlist of what to cook next.
   The prototype will decide this.
2. **Reading orders automatically.** Do the Ocado, Amazon and Deliveroo emails arrive at the
   Gmail address Term Time already reads (`SCHOOL_GMAIL_*`, read-only)? If so, step 6 could pick
   up delivery confirmations with no pasting. That would widen what the credential is used for,
   so it is a decision in its own right.

## Out of scope

Prices and costs; calorie tracking of any kind (targets, day totals, a diary); more than one
user; linking to CoParent; adding to a supermarket basket automatically; an OAuth MCP
connector for claude.ai or Claude Desktop.
