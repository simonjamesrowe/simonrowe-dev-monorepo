# Meals (`meals.simonrowe.dev`)

A private recipe, kitchen and shopping app for the site owner (spec `specs/053-meals/`). Every
route under `/api/meals/**` needs the `DEV_PORTAL_ADMIN` or `MEALS_USER` role, images included.
Uploading recipe bundles (`/api/meals/admin/**`) needs `DEV_PORTAL_ADMIN`.

## Letting someone else use it

Give them the Auth0 role **`MEALS_USER`**, never `DEV_PORTAL_ADMIN`: the admin role also opens the
whole CMS, Data Ops, the Software Factory console, Term Time's private tier, Langfuse,
Dependency-Track and the Temporal UI. `MEALS_USER` opens meals and nothing else.

1. They sign in once at `meals.simonrowe.dev` (with Google), so Auth0 has a user for them. They
   will see "This is a private app."
2. Auth0 dashboard → **User Management** → **Users** → them → **Roles** → **Assign Roles** →
   `MEALS_USER`. (Create the role under **Roles** first if it is missing; the Post-Login Action
   already copies every role into the token, so nothing else changes.)
3. They sign out and in again, so the new token carries the role.

The recipe library is shared, and they can add and edit recipes. Kitchen, Up next, shopping,
ratings and MCP tokens are per person, keyed on the Auth0 subject, so they start with an empty
kitchen of their own. A meals MCP token carries `MEALS_USER` whoever minted it.

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
**main site's** Auth0 application (its token already carries the roles claim), keeping the
token in local storage like CoParent. Photos are fetched with the token as blobs, because an
`<img src>` cannot send one. Locally: `http://meals.localhost:5173` (Vite rewrites that host to
the meals entry; the backend's default CORS list includes it).

### Signing in, and staying signed in

The app signs in by itself: with no session it goes straight to Auth0 (once per page load, so a
failed sign-in shows a button instead of looping), and comes back to the page it started on.

It remembers the device with **rotating refresh tokens** kept in local storage (scope
`offline_access`, `useRefreshTokens`). That needs two Auth0 settings, both on as of 2026-10-17:

- the main site's SPA application: **Refresh Token Rotation** on (it already was). Its
  refresh tokens last at most 30 days and expire after 15 idle days, so a device used at least
  once a fortnight stays signed in for a month;
- the `https://api.simonrowe.dev` API: **Allow Offline Access** on (turned on 2026-10-17;
  checked by signing in afresh and finding a refresh token in local storage).

If either is turned off, Auth0 issues no refresh token and the SDK falls back to the hidden
iframe (`useRefreshTokensFallback`), which Safari's tracking prevention blocks: the app still
works, but asks Auth0 again on each visit.

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

## The kitchen

`meal_inventory` holds one row per ingredient the owner has (id `<owner>|<name>`), and
`meal_inventory_events` logs every change with where it came from (quick add, cooked, shopping
delivered, an edit). Ingredients come in two tiers, set in the catalogue:

- **tracked** (meat, veg, dairy): a quantity in the ingredient's unit and an optional use-by;
- **staple** (cupboard): just `have`, `low` or `out`.

`KitchenMath` holds all the arithmetic, with no I/O. Rules that are easy to break:

- **A staple with no row is "check", not missing.** Nobody records every jar of paprika, and
  counting it as missing made almost nothing cookable in the prototype. Coverage weights a
  tracked line 2, a staple 1, and counts an unrecorded staple as 0.75 of a staple.
- **Assumed ingredients (salt, pepper, water) are ignored** everywhere: coverage, shopping,
  deductions.
- **Suggestions** score coverage, then using up what reaches its use-by within three days (most for
  today or tomorrow), then ratings (yours, and of similar-protein dishes), and push back "not again"
  and anything cooked in the last ten days. Each carries its reasons, which the Cook screen and the
  MCP show.
- **Cooked it** proposes deductions (what the recipe needs at the portions cooked, capped at what
  is in) and applies only what the owner ticks.

**Quick add** turns a note ("2 chicken breasts use by monday, out of rice"), a pasted Ocado,
Amazon or Deliveroo order, or a photo of the fridge or a receipt into proposed changes. The
model (`meals.model`, `MEALS_MODEL`, default `gpt-5.6-luna`) only proposes; nothing is written
until the owner reviews and applies the list. Its prompt carries today's date in
`Europe/London`, so "use by monday" means the right Monday. Unknown names come back as
`newIngredients` for the owner to accept, never silently added.

## Up next and shopping

There is no week planner. **Up next** (`meal_up_next`) is a shortlist of recipes and portions;
Dave's weeks add their recipes to it. The **shopping list** is Up next's needs minus the kitchen,
grouped by aisle, plus extras the owner typed, with unrecorded staples in a "Check the cupboard"
group. `meal_shopping` keeps per-line marks: in the basket, and at which shop (Ocado, Amazon,
Deliveroo). Each line carries a search term to paste into the shop. **Delivered** adds what was in
the basket to the kitchen (the list's quantity, or "have" for a staple) and clears it; pasting the
shop's order confirmation through quick add does the same with the real quantities.

## Adding and editing recipes

`/recipes/new` reads a draft from a **link** or a **photo**, or starts blank; `/recipes/:id/edit`
fixes any recipe, including a book one (which keeps its book and pages as its source).

- A link is fetched through `UrlFetcher.fetchPage`, the site's SSRF-guarded fetcher (private
  addresses refused, each redirect hop revalidated). A page with a schema.org `Recipe` in
  JSON-LD is read from that; otherwise from up to 15,000 characters of its text.
- **Reading a recipe takes about 45 seconds**, so its model call is bounded at 90s (quick add's
  is 45s) and the meals proxy location sets `proxy_read_timeout 120s`. nginx's default 60s would
  cut it off and show a 504 page.
- The draft's macros are marked estimated unless the page stated them.

## Claude Code (the private MCP server)

`/api/meals/mcp` is a **stateless streamable-HTTP** MCP server, separate from the public `/mcp`.
Make a token on the app's **More** page; it shows the command once:

```
claude mcp add --transport http meals https://meals.simonrowe.dev/api/meals/mcp \
  --header "Authorization: Bearer meals_..."
```

- Tokens (`meal_mcp_tokens`) are stored as SHA-256 hashes, at most ten live, revocable from the
  same page. `lastUsedAt` is updated at most every ten minutes.
- The path has its own `@Order(1)` security chain that accepts only a `meals_` token, never an
  Auth0 JWT, and every tool acts as the token's owner.
- Twelve tools (search, recipe, suggestions, what can I cook now, inventory read and update, Up
  next, shopping list, cooked, import from a URL) and two prompts (`plan-my-week`,
  `use-up-whats-expiring`).
- **The meals tools must never be a `ToolCallbackProvider` bean.** The public `/mcp` server
  collects every such bean, so one would publish the owner's kitchen to the world.
  `MealsMcpServerTest` asserts no meals tool is among them.

## Backup and restore

All nine collections (`meal_recipes`, `meal_ingredients`, `meal_plan_templates`, `meal_ratings`,
`meal_inventory`, `meal_inventory_events`, `meal_up_next`, `meal_shopping`, `meal_mcp_tokens`)
are in the full backup, and the photos travel as `meal-images/`.

- A restore replaces the collections and **adds** photos (files on disk are kept; a photo is
  named by the hash of its bytes, so a same-named file is the same photo).
- `RestoreService.ensureMealIndexes()` re-creates the indexes of `V058CreateMeals` and
  `V059CreateMealsKitchen`, because Mongock never re-runs a recorded unit. The unique token-hash
  index matters most: without it a token lookup could match two rows.
- Restoring `meal_mcp_tokens` brings back the tokens as they were at backup time, so a token
  revoked since then works again. Revoke it again after a restore.
- `MealsBackupCoverageTest` finds every `@Document` in `com.simonrowe.meals` and fails if one
  is missing from the backup or the restore order, so a new collection cannot go unbacked.
- Known gap, shared with CoParent receipts: the safety snapshot taken just before a restore
  (`BackupService.createLocalBackup`) does not include the private directories. Harmless while
  restoring them only adds files.
