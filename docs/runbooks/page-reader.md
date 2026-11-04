# Page reader

Reads article pages that refuse plain scrapers, in headless Chromium, so news summaries are
written from the article rather than its RSS blurb. openai.com is the reason it exists: every
`/index/<slug>` page answers a scraper with `403` and `cf-mitigated: challenge`.

## How a read flows

```
backend ArticleSourceTextProvider
  1. direct scrape (SitemapHtmlScraper)            — thin or refused?
  2. PageReaderClient ──POST /api/page-reads──► software-factory PageReadWorkflow (queue page-read)
                                                     └─ readPage activity (queue page-reader)
                                                          └─ page-reader container: Chromium
  3. ReaderFallbackClient (Jina Reader)            — only if 2 was thin
  4. stored fullContent, then the longest of everything
```

- The **workflow** is Java, in software-factory (`com.simonrowe.factory.pageread`), so retries,
  timeouts and run history sit with every other factory workflow.
- The **activity** is Node (`page-reader/`): Playwright plus an activity-only Temporal worker.
  Nothing in either JVM launches a browser.
- `POST /api/page-reads` is token-protected (the trigger token) and never routed by nginx. It
  waits up to `factory.pageread.wait-for` (100s) and answers `COMPLETED` with the result, or
  `RUNNING`/`FAILED`. Anything but `COMPLETED` + `READ` makes the backend move on to Jina.
- **No page-reader polling means an immediate 503**, not a 100-second wait: the factory counts
  activity pollers on `page-reader` before starting a read.
- Reads are keyed on the URL (`page-read-<sha256>`, `ALLOW_DUPLICATE_FAILED_ONLY`), so a second
  request for the same article reuses the finished read without opening a browser.

## Why it gets in

Cloudflare challenges `HeadlessChrome` in the user agent and nothing else that was tried.
The same headless Chromium saying `Chrome/<version>` loads openai.com first time; hiding
`navigator.webdriver` made no difference (checked 2026-11-04 from a home connection, then from
the Linux arm64 image). `userAgentFor` builds the user agent from the running browser's version.
This can stop working whenever Cloudflare tightens its rules, which is why Jina stays behind it.

## What keeps a page from reaching the stack

A page can redirect the browser anywhere, and **Playwright's request interception does not see
redirect hops** (checked: a 302 to a second local server never reached the route handler). So
the controls sit outside the page:

1. **Egress proxy** (`page-reader/src/egressProxy.ts`). Every browser connection goes through it,
   loopback included (`bypass: '<-loopback>'`). It resolves the name, requires **every** address
   to be public, connects to the address it checked (so DNS rebinding gains nothing), and allows
   ports 80 and 443 only. QUIC is disabled and WebRTC may use proxied UDP only, i.e. none.
   `readPage.test.ts` proves a redirect to a forbidden address is refused and never read; letting
   loopback bypass the proxy fails it.
2. **Network.** The container's only network is `page-reader`, shared with `temporal` alone.
3. **Container.** Non-root, `cap_drop: ALL`, `no-new-privileges`, read-only root with a 512 MB
   `/tmp` tmpfs. Chromium runs without its own sandbox (Playwright's default in a container).
4. **Bounds.** One read at a time, a fresh browser per read, a 90-second hard deadline that
   closes the browser, and images, media and fonts never fetched.

## Outcomes

| Outcome | Meaning | Retried? |
| --- | --- | --- |
| `READ` | the article text (Readability, up to 100,000 chars) | – |
| `BLOCKED` | a challenge title was still showing after 20s | no |
| `REFUSED` | HTTP error status, the proxy refused the address, or Chromium refused the port | no |
| `EMPTY` | loaded, no readable text | no |
| thrown | navigation timeout, crash, or the 90s deadline | once |
| `UnreadableUrl` | not an http(s) URL with a host | never |

`PageReadActivitiesContractTest` reads the Node source and fails when the queue, the activity
name (`readPage`), the outcomes or the result fields drift from the Java side. Without it a
mismatch shows as reads waiting out their schedule-to-close timeout, with no error.

## Rollout (first deploy)

Adding a service always holds the first deploy back (see `deploy.md`, "A decline does not clear
itself"), and this one also changes `temporal`'s networks. On the Pi, after the merge's deploy
reports `held-back`:

```bash
D=/home/simonrowe/workspace/simonjamesrowe/simonrowe-dev-monorepo
docker exec simonrowe-dev-monorepo-deployer-1 git -C "$D" reset --hard origin/main
cd "$D"
docker compose -f docker-compose.prod.yml pull page-reader
docker compose -f docker-compose.prod.yml up -d temporal page-reader software-factory
docker compose -f docker-compose.prod.yml up -d --no-deps deployer
```

Recreating `temporal` restarts it for a few seconds: running workflows pause and resume. The
deployer must be recreated last so its `FACTORY_DEPLOY_SERVICES`/`RECREATABLE` include
`page-reader`; until then later merges wedge on the same difference.

Then verify:

```bash
docker compose -f docker-compose.prod.yml ps page-reader          # healthy
docker logs simonrowe-dev-monorepo-page-reader-1 | grep RUNNING   # worker polling
```

and press Summarise on an OpenAI Engineering article. `Read page` lines with `outcome: READ`
in the page-reader's logs, and `read it in the page reader instead` in the backend's, mean it is
working.

## Switching it off

- `ARTICLE_PAGE_READER_ENABLED=false` (backend): skip straight to Jina.
- `FACTORY_PAGEREAD_ENABLED=false` (software-factory): the endpoint answers 503.
- Stopping the container has the same effect, immediately, through the poller check.

## Known gaps

- No Dependency-Track SBOM for the image yet.
- The status page lists it as a floating `latest` image: it reports no build commit.
- Memory is not measured on the Pi. `mem_limit: 1g` is a guess and inert anyway.
- The image is about 1 GB (Chromium and its system libraries), on the disk that filled in
  September; the deploy's image prune covers it like every other image.
