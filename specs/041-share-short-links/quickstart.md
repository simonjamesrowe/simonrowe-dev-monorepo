# Quickstart: Share links for blogs and news/events

**Feature**: 041-share-short-links

## Run it locally

```bash
# From the repo root. Sources backend/.env and frontend/.env.
./scripts/start.sh
```

`V029` runs on backend startup and mints a link for every existing blog post, aggregated
article and event. Confirm:

```bash
docker exec -i $(docker ps -qf name=mongo) mongosh simonrowe --quiet --eval '
  db.short_links.countDocuments({});
  db.short_links.find().limit(5).toArray();
'
```

Expect one document per piece of content, with the slug as `_id`:

```json
{ "_id": "exactly-once", "contentType": "BLOG",
  "contentId": "68f3a1c94b2e7d5a0c1f9e83", "clickCount": 0,
  "lastClickedAt": null, "createdAt": "2026-08-28T09:14:02Z" }
```

## Verify the unfurl without a chat client

This is the whole point of serving OG HTML to every client rather than sniffing
User-Agents: it is `curl`-verifiable.

```bash
SLUG=$(docker exec -i $(docker ps -qf name=mongo) mongosh simonrowe --quiet \
  --eval 'db.short_links.findOne({contentType:"BLOG"})._id')

curl -s "http://localhost:8080/s/$SLUG" | grep -E 'og:|twitter:|canonical|refresh'
```

Four things to check, in order of how quietly they fail:

1. **`og:image` is absolute** — starts with `http`. A relative one is dropped silently by
   every crawler, which presents as "the feature doesn't work" with no error anywhere.
2. **The status is `200`, not `302`.** `curl -i` should show no `Location` header.
   Crawlers follow redirects and would land on the SPA, which has no OG tags.
3. **`og:url` and `<link rel="canonical">` point at the real destination**, not at `/s/`.
4. **The `<noscript>`-visible `<a>` is present**, so a text browser still has a working
   link.

Then check the three destinations:

```bash
for t in BLOG ARTICLE EVENT; do
  S=$(docker exec -i $(docker ps -qf name=mongo) mongosh simonrowe --quiet \
       --eval "db.short_links.findOne({contentType:\"$t\"})?._id")
  [ -n "$S" ] && echo "$t -> $(curl -s "http://localhost:8080/s/$S" | grep -o 'rel="canonical" href="[^"]*"')"
done
```

Expected: `/blogs/{id}`, `/news-events?article={id}`, `/news-events?event={id}`.

And that an unknown slug is a themed 404 and **never** a redirect:

```bash
curl -si http://localhost:8080/s/does-not-exist | head -1   # HTTP/1.1 404
curl -si http://localhost:8080/s/does-not-exist | grep -i location   # nothing
```

## Verify click counting

The counter is the motivating half of the feature, and the thing most likely to be quietly
wrong. Two directions:

```bash
BEFORE=$(docker exec -i $(docker ps -qf name=mongo) mongosh simonrowe --quiet \
  --eval "db.short_links.findOne({_id:\"$SLUG\"}).clickCount")

# A human — counted.
curl -s -A "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)" \
  "http://localhost:8080/s/$SLUG" > /dev/null

# An unfurler — NOT counted.
curl -s -A "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)" \
  "http://localhost:8080/s/$SLUG" > /dev/null
curl -s -A "facebookexternalhit/1.1" "http://localhost:8080/s/$SLUG" > /dev/null

AFTER=$(docker exec -i $(docker ps -qf name=mongo) mongosh simonrowe --quiet \
  --eval "db.short_links.findOne({_id:\"$SLUG\"}).clickCount")

echo "$BEFORE -> $AFTER"   # expect exactly +1
```

`+3` means the unfurler filter is not wired in — which matters because a single paste into
Slack fetches the link once before any human clicks it, and LinkedIn, WhatsApp and iMessage
all do the same. Without the filter most of the count is robots reading metadata.

## Verify the frontend

Local dev proxies `/s` to the backend via `frontend/vite.config.ts`, so
`http://localhost:5173/s/<slug>` works too.

1. **Blog detail** — <http://localhost:5173/blogs> → open a post → Share in the header.
   On desktop the button swaps to a tick and "Copied" for two seconds; paste and confirm
   you got `https://simonrowe.dev/s/<slug>` (the production base, even locally — that is
   intentional, the link is for sharing).
2. **Blog listing cards** — the same control on each card. Pressing it must **not**
   navigate: the card is an `<a>`, so the handler calls `preventDefault`/`stopPropagation`.
3. **News cards** — Share is the fourth control after Listen, Summarise and Favourite.
   Narrow the window to ~375px and confirm the row does not wrap badly. If it does, the fix
   is icon-only, not removal.
4. **News deep link** — open `http://localhost:5173/news-events?article=<id>` in a fresh
   tab. The summary drawer for that article opens and the page scrolls to the card.
5. **The case most likely to be missed** — pick an article id that is **not** on page one:

   ```bash
   docker exec -i $(docker ps -qf name=mongo) mongosh simonrowe --quiet --eval '
     db.aggregated_articles.find({visible:true}).sort({publishedDate:-1}).skip(40).limit(1).toArray()'
   ```

   Open `?article=<that id>`. It must fetch that article specifically and open the drawer.
   A page that loads and silently does nothing is the bug this step exists to catch.
6. **Native share sheet** — in device emulation with `navigator.share` stubbed, press
   Share and dismiss the sheet. Nothing should be reported as an error (`AbortError` is
   swallowed).

## Verify the admin views

<http://localhost:5173/admin/short-links> — slug, title, type, clicks, last clicked;
click a column header to sort. <http://localhost:5173/admin/blogs> — a click-count column.

Both require the admin role; signed in as `admin@simonrowe.dev` (password in `.env`).

## Run the tests

```bash
cd backend && ../gradlew test --tests 'com.simonrowe.shortlink.*' \
                             --tests 'com.simonrowe.migration.changeunits.V029*' \
                             --tests 'com.simonrowe.auth.SecurityConfigTest'
cd backend && ../gradlew checkstyleMain checkstyleTest
cd frontend && npm test
```

## After deploying to production

**Do not skip this.** `frontend/nginx.conf` is bind-mounted from the deploy directory in
production, not baked into the image, so pulling a new frontend image alone does not apply
the new `location /s/`. A normal deploy does pick it up — `sync-config` fast-forwards the
deploy checkout and `frontend` is in the recreate allowlist — but verify rather than
assume:

```bash
curl -i https://simonrowe.dev/s/<known-slug>
```

A `200` with OG tags means the route is live. The SPA's HTML (a `<div id="root">` and no
`og:` tags) means nginx is still sending `/s/` to the frontend, and every link already
shared is unfurling as the generic site title.

Then confirm the real thing:

```bash
curl -s -A "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)" \
  https://simonrowe.dev/s/<known-slug> | grep 'og:image'
```

and paste one link into Slack, LinkedIn, WhatsApp and iMessage — SC-003 needs four
platforms, and each has its own quirks about image dimensions and caching that no local
check can stand in for.
