# Quickstart: UX Top-10 Improvements

How to build, run, verify and demo this feature.

---

## 1. Bring up a local stack with production-like data

FR-042 requires verification against production-like data — the blog
`contentType` split, the skill-group icons and the employer logos are all only
meaningful against real content.

```bash
# from the repo root
./scripts/start.sh          # backend :8080 (management :8082), frontend :5173
```

Then restore the latest production backup through the admin Data Ops UI — use the
`prod-data-restore` skill; do **not** run `mongorestore` against prod data
directly. If ports collide with another Conductor workspace, use the `local-env`
skill.

Mongock runs at boot with `MONGOCK_ENABLED` defaulting to `true`, so `V015`–`V017`
apply automatically on the first backend start after the restore.

---

## 2. Build and test

```bash
# Backend — tests, style, coverage
cd backend && ../gradlew test
../gradlew :backend:checkstyleMain :backend:checkstyleTest   # maxWarnings = 0
../gradlew :backend:jacocoTestCoverageVerification           # minimum 0.78

# Frontend — unit tests and lint
cd frontend && npm test
npm run lint
npm run build          # tsc -b && vite build — catches type errors the tests miss

# End-to-end (requires the stack from step 1 to be running)
npm run e2e            # playwright --project=local
```

Native-image check for the bundled SVG resources (D12 risk):

```bash
cd backend && ../gradlew bootBuildImage
```

Confirm the icons still resolve from the resulting container — classpath resources
must be reachable in the GraalVM native image, and this is the only way to prove
it.

---

## 3. Verify each story by hand

Run through this in **both light and dark themes**, at 1440px and at 390×844.

### US1 — home page below the hero
- `http://localhost:5173/` → scroll. Currently strip, employer logo row, three
  engineering posts, contact CTA band, footer — in that order.
- Click an employer logo → lands on `/experience`.
- Every logo legible in both themes; none clipped or invisible.
- Block `GET /api/jobs` in devtools and reload: the two jobs-backed sections
  disappear quietly; the page still renders.

### US2 — blog content types
- `/blogs` → the Engineering tab is preselected; no digest posts listed.
- The featured card is an engineering post.
- Weekly Digest tab → only digests. All → both.
- Card CTA reads "Read post".
- `curl -s localhost:8080/api/blogs | jq '[.[].contentType] | group_by(.) | map({(.[0]): length}) | add'`
  → expect roughly `{"ENGINEERING": 28, "DIGEST": 15}`.
- Re-run idempotency: restart the backend and re-check the counts are identical.

### US3 — no dead ends
- `/blog` → `/blogs`, and the browser Back button leaves the site rather than
  bouncing between the two URLs (`<Navigate replace>`).
- `/blog/<a real post id>` → that post at `/blogs/<id>`.
- `/nonsense` → the 404 page inside the normal nav/footer chrome, with working
  links to `/` and `/blogs`, and a 404-appropriate browser tab title.

### US4 — error handling
- Stop the backend, then load `/blogs`, `/news-events`, `/experience`, `/profile`,
  `/mcp`. Each shows a correctly-titled error frame — none says "Unable to load
  homepage" — with a Retry button.
- Restart the backend and press Retry: content appears without a page reload.
- In devtools, throttle one `/api/blogs` request to fail once: the page recovers
  silently (the automatic single retry) with no error shown.

### US5 — mobile hero
- Resize to 390×844. Badge and tagline visible, tagline on one line, exactly two
  prompt chips. The chat input is inside the first screen with no large gap.
- Tapping a chip opens the chat with that prompt.

### US6 — news paging
- `/news-events` → devtools Network shows `size=24`, not `size=100`.
- "Load more" appends a page; scroll position is preserved.
- Reach the last page → the button disappears.
- Select a source chip → a fresh `GET /api/news?page=0&size=24&source=...`.
- `curl -s localhost:8080/api/news/sources | jq` lists every source, including any
  with no article in page 0.
- Toggle favourites → unchanged behaviour.

### US7 — skills and assets
- `/experience` → every skill shows a level word matching its bar (spot-check a
  9, a 7, a 5 and a 3).
- Every skill group has an icon; every employer a sharp, uncropped logo.
- Inspect a rating with a screen reader or the accessibility inspector: the
  announced text matches the visible word.

### US8 — chrome and copy
- "Get in touch" and "Download CV" have solid fills, not fading to white; hover
  still lifts. Check contrast in both themes.
- Contact form submit reads "Send message"; submit it and confirm the success and
  failure copy use the same verb.
- Signed out: **no** admin icon in the desktop nav, no "Admin" item in the mobile
  menu. Visiting `/admin` still redirects to Auth0.
- Sign in as `admin@simonrowe.dev` → the admin link appears.
- Profile page: the two GitHub links have distinct labels.
- Every page's browser tab title identifies the page and the site.

---

## 4. The one blocking gate

**Do not write `V016InstallSkillAndCompanyIcons` until the icon and logo set has
been approved** (FR-032). The implementer must render the proposed marks as a
preview grid, in both themes, and get an explicit yes. The manifest and its
sources are in `contracts/asset-manifest.md`.

Everything else in the feature can proceed in parallel with that approval.

---

## 5. Demo script (5 minutes)

1. Land on the home page, scroll the whole way down — this is the headline change.
2. Follow the "Read the blog" link into `/blogs`; point out that the Engineering
   tab is the default and the digests are one click away rather than in the way.
3. Open `/blog/<id>` from an old shared link to show the redirect, then a nonsense
   URL to show the 404.
4. Resize to mobile and show the hero now says something.
5. Stop the backend and reload to show a real error state with a working retry.

---

## 6. Ship

```bash
git push -u origin simonrowe/ux-review-simonrowe-dev
gh pr create --base main
```

CI must be green before merge. Deploy with the `prod-deploy` skill; the Mongock
change units run automatically when the new backend container starts on the Pi.
Take a backup first with `prod-backup-ops` — `V015`–`V017` mutate `blogs`,
`skill_groups`, `jobs` and `social_medias`, and while each is idempotent and
reversible, a pre-change backup is the cheap insurance.
