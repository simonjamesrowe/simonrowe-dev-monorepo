## Four sources, one place to ask

Everything goes through the same pipeline. Each source is fetched, split into dated events and
searchable text, tagged with the year groups it applies to, and stored. Nothing reaches the public
chat until it is either something the school published itself or something I have approved.

![From source to answer. The school website, calendar feed, school mailbox and pasted notes are fetched, their dates pulled out and stored in MongoDB and Elasticsearch, while email waits in an approval queue.]({{media:sources.svg}})

| Source | How often | What it brings |
| --- | --- | --- |
| School mailbox | Every 30 minutes | Gmail, read-only. Only the school's own sending addresses are let in, matched on the address rather than the display name. |
| Calendar feed | Every 6 hours | One iCal request. The most authoritative source, so it wins when two sources describe the same event. |
| Website crawl | Every 12 hours, about 30 minutes each | Every page and PDF, at the ten seconds a page that the school's robots.txt asks for. |
| Notes and photos | Whenever I paste one | Messages from the parents' WhatsApp group and photographed letters. Links in a note are fetched straight away. |

## A day in the life of the ingester

Each source runs as often as it is worth. The calendar is a single request, so it can run often.
The website crawl takes half an hour, so running it every hour would mean crawling almost all the
time.

![Twenty-four hours of scheduled work: 48 mailbox syncs, four calendar refreshes, two website crawls of about 30 minutes each, and notes pasted at any time.]({{media:schedule.svg}})

The schedules use a fixed delay rather than a fixed rate. The next crawl starts twelve hours after
the last one finished, so a slow crawl can never overlap the next one. Each interval is a property
with a default, so the cadence can change without a deploy.

## How fast a newsletter reaches the chat

The newsletter usually arrives on a Friday afternoon. This is the path it takes before a parent
can ask about it.

![A newsletter's path: the next mailbox sync picks it up within 30 minutes. Mail from the school that links to its own newsletter page is followed and answerable at once; other school email waits for approval; anything else is ignored.]({{media:newsletter.svg}})

In September 2026 the school moved its newsletter onto its parent portal, so the email became a
covering sentence and a link. Term Time follows that one kind of link automatically, because the
website crawl already reads the school's site. Any other link in an email is recorded and never
followed.

## Where the trust boundary sits

- **Everything starts restricted.** A document becomes public only because the school published
  it on its own site or calendar, or because I approved it.
- **A classifier proposes and I decide.** The model suggests whether an email could be public and
  says why. The approval queue shows the suggestion, and approving is the only thing that changes
  a document's tier.
- **Links are copied, never invented.** A booking link the extraction model returns is discarded
  unless it appears word for word in the source.
- **Year groups come from the source.** A page under the school's Year 3 section is tagged Year 3.
  A note can be tagged when I paste it, and anything untagged is treated as not stated rather
  than whole-school.
