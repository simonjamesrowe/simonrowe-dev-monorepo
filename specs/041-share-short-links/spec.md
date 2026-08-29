# Feature Specification: Share links for blogs and news/events

**Feature Branch**: `041-share-short-links`

**Created**: 2026-08-28

**Status**: Draft

**Input**: User description: "Share links for blogs and news/events — implement the design at docs/superpowers/specs/2026-08-28-share-short-links-design.md"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Share a blog post that unfurls (Priority: P1)

A visitor finishes reading a blog post and wants to pass it to a colleague. They press
Share in the post header. On a phone the operating system's share sheet opens; on a
desktop the link is copied to the clipboard and the button confirms with "Copied". The
link they get is short and says what the post is about — not a 24-character identifier.
When they paste it into Slack, LinkedIn or WhatsApp it expands into a rich preview with
the post's title, description and image.

**Why this priority**: This is the whole feature in one journey — a readable link, a
one-press share, and a preview that makes the link worth clicking. Without it nothing
else matters.

**Independent Test**: Publish a blog post, press Share on its page, and confirm the
copied link is short and readable. Fetch that link with a command-line tool and confirm
the response contains title, description and image preview metadata pointing at the
post. Open the link in a browser and confirm it lands on the post.

**Acceptance Scenarios**:

1. **Given** a published blog post with a share link, **When** a visitor presses Share on
   a desktop browser, **Then** the short link is placed on the clipboard and the control
   confirms for a couple of seconds before returning to its normal state.
2. **Given** the same post, **When** a visitor presses Share on a device that offers a
   native share sheet, **Then** the sheet opens carrying the post title and the short link.
3. **Given** a visitor opens the native share sheet and then dismisses it without
   choosing a destination, **When** the dismissal happens, **Then** nothing is reported as
   an error and the control returns to its normal state.
4. **Given** a short link for a blog post, **When** any client requests it, **Then** the
   response carries preview metadata (title, description, absolute image address, canonical
   address) and takes a browser on to the post.
5. **Given** a short link, **When** it is opened, **Then** the address is at most 20
   characters after the site prefix and is composed of whole words from the title.

---

### User Story 2 - Share a news item and land on the first-party summary (Priority: P1)

A visitor sees an aggregated news article on the news and events page, presses Share, and
sends the link on. The recipient opens it and arrives on the news and events page with
that article's summary panel already open — seeing the AI summary and the narration audio
rather than being bounced straight to the original publisher.

**Why this priority**: Today news and events cards have no first-party destination at all,
so "sharing" one can only mean handing out someone else's URL. This story is what makes
the news side of the feature worth anything.

**Independent Test**: Press Share on a news card, open the resulting link in a fresh
browser session, and confirm the summary panel for that exact article opens and the page
scrolls to the card.

**Acceptance Scenarios**:

1. **Given** an aggregated article with a share link, **When** a visitor opens that link,
   **Then** the news and events page loads with that article's summary panel open.
2. **Given** an aggregated event with a share link, **When** a visitor opens that link,
   **Then** the news and events page loads focused on that event.
3. **Given** a shared link to an article that has since dropped off the first page of
   results, **When** a visitor opens it, **Then** the system retrieves that specific item
   and opens its panel rather than loading the page and silently doing nothing.
4. **Given** a news card at mobile width already carrying Listen, Summarise and Favourite
   controls, **When** Share is added, **Then** the controls remain usable and legible.

---

### User Story 3 - See whether shared links are being opened (Priority: P2)

The site owner shares links over a week and wants to know whether anyone opened them.
They sign in to the admin area and see a table of every short link — its address, the
content it points at, its type, how many times a person opened it, and when it was last
opened — sortable so the most-opened links surface first. The blog list also shows a
click count per post.

**Why this priority**: Click visibility is the motivating half of the feature, but the
links are useful the day they exist and the counts only become interesting once links are
in circulation. It can ship a step behind the sharing itself.

**Independent Test**: Open a short link several times from a browser, then confirm the
admin table shows the corresponding count and a recent last-opened time.

**Acceptance Scenarios**:

1. **Given** a short link opened three times by a person, **When** the owner views the
   shared-links table, **Then** the count reads 3 and the last-opened time is recent.
2. **Given** a short link pasted into a chat tool that fetches it to build a preview,
   **When** no person has yet clicked it, **Then** the count stays at 0.
3. **Given** the shared-links table, **When** the owner sorts by clicks, **Then** rows
   reorder by count.
4. **Given** the blog admin list, **When** it is displayed, **Then** each post shows its
   click count.

---

### User Story 4 - Existing content becomes shareable without republishing (Priority: P2)

Every blog post, aggregated article and event that already exists gets a share link
without anyone editing or re-saving it, and every item created from now on gets one at the
moment it is created or ingested.

**Why this priority**: Without this the feature only applies to content created after
release, which for an archive of existing posts is most of the value missing.

**Independent Test**: With existing content in place, run the release process and confirm
every pre-existing item now offers a Share control. Run it a second time and confirm no
duplicate links are created.

**Acceptance Scenarios**:

1. **Given** blog posts, articles and events that predate this feature, **When** the
   release completes, **Then** each has exactly one share link.
2. **Given** the backfill has already run, **When** it runs again, **Then** no additional
   links are created and no existing link changes.
3. **Given** a blog post is saved a second time, **When** the save completes, **Then** its
   share link is unchanged — a previously shared link never stops working.
4. **Given** two different items with very similar titles, **When** both are given links,
   **Then** each has its own distinct address and both stay within the length limit.

---

### Edge Cases

- **Unknown or mistyped address**: returns a themed not-found page, never a silent
  redirect to the home page — a typo must not look like a working link.
- **Title with no usable Latin characters** (emoji-only, or entirely non-Latin script):
  the address falls back to a short random code rather than being empty.
- **Title with accented characters**: accents are reduced to their plain letters.
- **Title whose first word alone exceeds the length limit**: the address is still produced
  and still within the limit.
- **Content that has no share link yet** (created in the window before minting): the item
  renders normally with no Share control, rather than offering a broken link.
- **Content with no image**: preview metadata falls back to a committed default share
  image, and the image address is always absolute — a relative one is dropped silently by
  preview crawlers.
- **Content image hosted by an external publisher**: used as-is, since it is the image the
  card already shows.
- **Counter write fails**: the visitor is still taken to the content. The count is the
  least important thing the address does.
- **Clipboard unavailable** (non-secure context, e.g. plain-HTTP local development): a
  fallback copy path is used so the control still works.
- **A client that runs no scripts** (text browser, stripped-down preview client): a plain
  visible link to the destination is present in the response.
- **Restoring the site from a backup**: short links survive, because the addresses are
  already pasted in other people's conversations and dropping them breaks links in the
  wild.

## Requirements *(mandatory)*

### Functional Requirements

#### Share addresses

- **FR-001**: System MUST issue exactly one short, permanent share address per blog post,
  aggregated article and aggregated event.
- **FR-002**: Each share address MUST be at most 20 characters, unique across all content
  types, and derived from the content's title using whole words — never a mid-word cut.
- **FR-003**: System MUST reduce accented characters to their plain equivalents and
  collapse punctuation and spacing into single separators when deriving an address.
- **FR-004**: When a derived address is already taken by different content, System MUST
  produce a distinct address that still respects the length limit.
- **FR-005**: When a title yields no usable characters, System MUST fall back to a short
  random code.
- **FR-006**: Issuing an address MUST be repeatable without effect: asking again for the
  same content MUST return the address already issued, unchanged.
- **FR-007**: System MUST issue addresses when a blog post is saved, when an article or
  event is ingested, and retrospectively for all content that already exists.

#### Opening a share address

- **FR-008**: Opening a share address MUST return a response, to every kind of client, that
  carries preview metadata — title, description, image, canonical address, address type,
  and a large-image preview hint.
- **FR-009**: The preview image address MUST always be absolute. A site-hosted image path
  MUST be made absolute against the site address; an already-absolute address MUST pass
  through unchanged; anything else MUST fall back to a committed default share image.
- **FR-010**: The response MUST take a normal browser on to the destination without the
  visitor acting, and MUST also contain a visible working link for clients that do not act
  on that automatically.
- **FR-011**: Destinations MUST be: a blog post's own page for a blog; the news and events
  page with the article's panel opened for an article; the news and events page focused on
  the event for an event.
- **FR-012**: An unrecognised address MUST return a not-found response with the site's
  themed body, and MUST NOT redirect anywhere.
- **FR-013**: Share addresses MUST be reachable without signing in, and MUST remain so.

#### Counting

- **FR-014**: System MUST record a per-address count of opens and the time of the most
  recent one.
- **FR-015**: System MUST NOT count a request made by a link-preview service. Preview
  services from at least the major chat, social and messaging platforms MUST be recognised,
  alongside a general catch-all for self-identified robots.
- **FR-016**: A failure to record a count MUST NOT prevent the visitor reaching the
  content.

#### Reading

- **FR-017**: Blog summaries, blog details, article records and event records returned to
  the site MUST each carry the item's full share address, ready to use without the site
  assembling it.
- **FR-018**: That address MUST be allowed to be absent, and the site MUST hide the Share
  control when it is.
- **FR-019**: Looking up addresses for a list of items MUST be done in a single batched
  operation per list, not one lookup per item.

#### Sharing control

- **FR-020**: The Share control MUST appear on the blog post page, on blog listing cards,
  and on news and event cards.
- **FR-021**: Pressing Share MUST open the device's native share sheet where one is
  offered, and otherwise MUST copy the address and confirm visibly for about two seconds.
- **FR-022**: A visitor dismissing the native share sheet MUST NOT see an error.
- **FR-023**: A copy path MUST work even where the modern clipboard facility is
  unavailable.

#### Deep links into news and events

- **FR-024**: The news and events page MUST read an article or event identifier from the
  address and open that item's panel.
- **FR-025**: When the identifier is not among the items currently loaded, System MUST
  fetch that specific item so the panel still opens.
- **FR-026**: News and event cards MUST carry identifiers so the page can scroll to the
  shared item.

#### Administration

- **FR-027**: The blog admin list MUST show a click count per post.
- **FR-028**: An admin-only shared-links view MUST list every address with its title,
  content type, click count and last-opened time, sortable by those columns.
- **FR-029**: The shared-links data MUST be reachable only by an administrator.

#### Durability

- **FR-030**: Share addresses MUST be included in the site's backups and restored by the
  restore process, so addresses already circulating keep working.

### Key Entities

- **Short link**: the addressable share record. Holds the address itself (which is its
  identity), which kind of content it points at, which item, how many times a person has
  opened it, when it was last opened, and when it was created. Exactly one exists per item.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A share address for any piece of content is at most 20 characters and is
  readable aloud — a person hearing it can tell roughly what it points to.
- **SC-002**: 100% of blog posts, aggregated articles and aggregated events in the system
  have a share address after release, and re-running the retrospective step adds none.
- **SC-003**: A share address pasted into each of the major chat, social and messaging
  platforms displays a preview with a title, a description and an image — verified on at
  least four platforms.
- **SC-004**: Opening a share address puts the visitor on the intended content, including
  the correct summary panel for a news item, in a single step with no visible intermediate
  page.
- **SC-005**: The recorded count for an address matches the number of human opens; pasting
  a link into a chat tool without clicking it leaves the count unchanged.
- **SC-006**: Sharing a piece of content takes one press, from any of the three places the
  control appears, on both phone and desktop.
- **SC-007**: An address that does not exist produces a not-found page 100% of the time,
  and never a redirect.
- **SC-008**: Displaying a list of 24 news items adds no more than one additional data
  lookup for share addresses.
- **SC-009**: Every address issued before a backup is still resolvable after a restore.

## Assumptions

- Where the design document and this specification could differ, the design document at
  `docs/superpowers/specs/2026-08-28-share-short-links-design.md` is authoritative; this
  specification restates it in outcome terms.
- The share address prefix is `/s/` on the primary site host, giving addresses of the form
  `https://simonrowe.dev/s/<address>`; the 20-character limit applies to the address part
  only.
- The description used in preview metadata comes from the content's existing summary or
  excerpt field; no new editorial field is introduced.
- Native share sheets are assumed present on mobile browsers and absent on desktop; the
  behaviour is chosen by capability detection, not by device sniffing.
- Preview services are identified by how they describe themselves in their requests. This
  is not reliable in general, and is accepted here because the only cost of missing one is
  a slightly inflated count — never a broken preview or a broken redirect.
- The click count is a plain number, not a stream of events; who clicked, from where, and
  when each individual click happened are deliberately not recorded.
- Blog posts keep their existing canonical page address; the share address is an additional
  entry point, not a replacement, and links copied from the browser address bar continue to
  behave as they do today.
- Preview metadata on the canonical blog and news addresses is explicitly out of scope, as
  are per-click event records, hand-edited addresses, and share addresses for any other
  content type.
