# Email newsletter sources (TLDR)

News & Events can take stories from email newsletters as well as from sites. The four TLDR
editions are the first: **TLDR**, **TLDR Dev**, **TLDR Product** and **TLDR AI**, all sent from
`dan@tldrnewsletter.com` to Simon's personal Gmail.

## How it works

- Each edition is a `content_sources` row with strategy `EMAIL_NEWSLETTER`, seeded by
  `V051SeedTldrNewsletterSources`. `feedUrl` holds the **sender address**, and `name` must equal
  the edition's **From display name**, because all four come from one address. Rename a source
  and it stops matching.
- The mailbox is read with Term Time's read-only credential (`SCHOOL_GMAIL_*`). It's the same
  personal mailbox, and nothing is written back to it. If the credential is missing or revoked,
  each edition's `lastError` says so on `/admin/content-sources` instead of looking like a quiet
  newsletter.
- Every 6-hourly aggregation run, `NewsletterIngestService` does the following:
  1. Lists the last 3 days of mail from the sender.
  2. Keeps only messages where the address matches exactly, the display name equals the source
     name, and **Google recorded a passing DKIM signature for the sender's domain**. Gmail's
     `from:` search also matches display names, and a `From` header can claim anything.
  3. Parses each issue's HTML into stories (`TldrIssueParser`) and drops the `(Sponsor)` slots.
     It decodes click-tracking links rather than following them, so it never records a click.
     It resolves `links.tldrnewsletter.com` short links with one request, with redirects off.
  4. Drops stories already read (`newsletter_candidates`) or already on the site.
  5. Scores the rest against the **hearted** News & Events articles (`InterestProfile`). It uses
     `text-embedding-3-small`, and the score is the best cosine similarity to any one heart.
  6. Records every story in `newsletter_candidates`. Those scoring at least
     `aggregation.newsletter.relevance-threshold` (default **0.50**) are saved as articles,
     capped by `max-accepted-per-run` (default **15**) per edition per run. The rest wait in
     the review queue.
- A saved story keeps TLDR's headline and summary, which means no classifier LLM call. The linked
  page is fetched for its image, date and body, but only when its host resolves to a public
  address. With no image, an AI one is generated, as for every other source.

## Reviewing

`/admin/newsletter-review` lists stories by status: waiting, saved automatically, promoted and
dismissed. Each row shows its score, the hearted article it was closest to, and the reason for its
status.

- **Promote** saves a story exactly as an automatic save would.
- **Dismiss** records the decision, so the story is never offered again.
- Only saved stories reach search, embeddings or chat. A queued story costs one embedding.

## Tuning

- **Too much or too little getting through:** set `NEWSLETTER_RELEVANCE_THRESHOLD` (or edit
  `application.yml`). Calibration on 2026-10-06 (36 stories, 51 hearts) accepted 8 at 0.50: the
  agent, Java and Claude stories. Product, hardware and general-science stories went to review.
- **The profile is the hearts.** Heart more of what you want and the next run scores
  accordingly. With nothing hearted, everything waits for review.

## Diagnosing "nothing from TLDR"

1. Check `/admin/content-sources`. A `lastError` on a TLDR row means the mailbox could not be read.
2. Check the backend log for the per-run line:
   `Newsletter TLDR Dev: N issues, N new stories, N saved, N queued for review`.
   - `0 issues` means the sender, display name or DKIM did not match. A
     `Skipping message ... no passing DKIM signature` WARN names which.
3. If issues arrive but no stories are found, TLDR has changed its markup. `TldrIssueParserTest`
   holds two real issues to compare against.

## Adding another newsletter

Seed a source with a change unit:

- strategy `EMAIL_NEWSLETTER`
- `feedUrl` set to the sender address
- `name` set to the exact display name

The parser is TLDR-specific, keyed on the bold headline that ends in a `(N minute read)` style
label. A newsletter with a different shape needs its own parser.
