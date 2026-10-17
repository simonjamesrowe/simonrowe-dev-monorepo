# 053 · Meals: build plan

Builds `spec.md` as five stacked pull requests, each useful without the next and each green
before the next is opened on top of it. The prototype the plan was validated against lives in
`.context/meals-prototype/` (gitignored, local only).

## Changes from the spec, decided while planning

- **The books arrive as an import bundle, not by reading PDFs in the backend.** The five books
  were already extracted while prototyping, one agent per half-book, with titles, macros and
  servings checked against the rendered pages where the text layer was ambiguous, then merged
  into standard ingredients. Building a server-side PDF-rendering and vision pipeline to read
  the same five books a second time is the wrong tool for a one-off load. Instead:
  - `POST /api/meals/admin/bundles` takes a zip (`manifest.json` plus `photos/`), produced by
    `.context/meals-prototype/tools/bundle.py`, one per book to stay under the upload limit.
  - Importing is insert-if-absent by recipe id, so a re-upload adds nothing and never
    overwrites an edit.
  - The bundle and its tooling stay out of the repo, as the copyright section requires. Test
    fixtures are synthetic bundles.
  - The model-assisted review screen the spec describes arrives with step 5 (recipes from a
    link or a photo), where a draft genuinely needs reviewing. It doubles as the editor for a
    book recipe the extraction got wrong.
- **Up next replaces the week planner** (decided with Simon after the prototype). There are no
  days or slots: a shortlist of recipes and portions, and the shopping list is built from it.
  Dave's weeks are templates that add their recipes to Up next.
- **Unrecorded cupboard staples are "check", not "missing".** Without this, almost nothing
  counted as cookable in the prototype. They sit in a "Check the cupboard" group on the
  shopping list with a one-tap "Have it".
- **Photos are fetched with the bearer token** and shown from object URLs, because an `<img>`
  cannot send an `Authorization` header and every route must be authenticated.

## Pull requests

| # | Branch | Scope |
| --- | --- | --- |
| 1 | `feat/meals-library` | Package, model, indexes, security, image store and volume, bundle import, recipes, ratings, templates API; backup and restore; the `meals` frontend entry with Cook (ratings-based), Recipes, recipe page, cook mode and Cooked it; nginx, CORS, compose |
| 2 | `feat/meals-kitchen` | Inventory and its event log, quick add (model, structured output), Cooked it deductions, suggestions endpoint, Kitchen screen, Cook screen on coverage |
| 3 | `feat/meals-up-next-shop` | Up next, templates in the UI, shopping list, basket marks, delivery and pasted-order reconciliation |
| 4 | `feat/meals-mcp` | Personal access tokens, the private MCP server at `/api/meals/mcp`, its tools and prompts |
| 5 | `feat/meals-recipe-import` | Recipes from a link (JSON-LD then model) or a photo, the draft review and edit screen |

## Operator steps (Simon), before PR 1 is useful in production

1. ~~Cloudflare DNS~~: nothing to do, the `*.simonrowe.dev` wildcard already reaches the edge
   nginx (checked: `https://meals.simonrowe.dev/healthz` answered 200 on 2026-10-17).
2. ~~Auth0~~: done 2026-10-17. The main site's SPA application lists
   `https://meals.simonrowe.dev/` and `http://meals.localhost:5173/` as callback, logout and
   web origins. Meals signs in with that application, so no new application or secret.
3. **After the frontend PR deploys, recreate nginx.** The deploy only `restart`s it, and the
   proxy config is a single-file bind mount, so a restart keeps serving the old file and the
   meals server block is not live until
   `docker compose -f docker-compose.prod.yml up -d --force-recreate --no-deps nginx`.
4. Then upload the three bundles from `.context/meals-bundles/` on the Import page.
5. Only then add `meals.simonrowe.dev` to `monitor-prod.sh` and `prod-heartbeat.sh` (held back
   from the frontend PR: before step 3 the watchdog would read the missing block as a broken
   frontend and restart it).
