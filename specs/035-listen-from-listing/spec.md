# Feature Specification: Listen from the listing

**Feature Branch**: `035-listen-from-listing`

**Created**: 2026-08-26

**Status**: Draft

**Input**: User description: "Implement 'Listen from the listing' per the design document at `docs/superpowers/specs/2026-08-26-listen-from-listing-design.md`: make generated narration audio playable directly from the /blogs and /news-events listing pages via a per-card Listen button and a persistent docked mini-player that survives navigation, backed by a new public bulk-ready-narrations endpoint, with POST /api/blogs/{id}/narration tightened to require auth."

## Overview

Generated audio already exists for blog posts and for AI summaries of aggregated news
articles, but a reader can only reach it by drilling into a single item: a blog post's
narration lives on its detail page, and a summary's narration lives inside the news summary
drawer. Someone browsing the blog listing or the news feed cannot start listening without
first committing to one item, and playback stops the moment they navigate back to carry on
browsing.

This feature puts a listen control on the cards themselves, and moves playback into a
single persistent player that keeps playing while the reader carries on browsing, filtering
and loading more.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Play existing audio straight from a listing card (Priority: P1)

A reader lands on the blog listing. Some posts already have generated audio. Those cards
show a play control labelled with the audio's approximate length, so the reader can see at
a glance which items are listenable and how long they are. Pressing it starts playback
immediately, with no waiting. A player docks to the bottom of the viewport. The reader
scrolls on, changes the tag filter, clicks through to a different page — the audio keeps
playing and the player stays put.

**Why this priority**: This is the whole point of the feature and the only story that needs
no generation chain, no authentication and no new write path. Shipped alone it already
delivers the value: browse and listen at the same time.

**Independent Test**: Seed narration audio for one blog post and one aggregated article,
open each listing page, confirm the card advertises a duration, press it, confirm audio
plays and survives a filter change and a route change.

**Acceptance Scenarios**:

1. **Given** a blog post with ready audio, **When** the reader opens the blog listing,
   **Then** that post's card shows a play control labelled with the audio's approximate
   duration.
2. **Given** the reader presses that control, **When** the audio loads, **Then** playback
   begins without any intermediate waiting state and a player appears docked at the bottom
   of the viewport showing the item's title.
3. **Given** audio is playing, **When** the reader changes the listing's filter, loads more
   items, or navigates to another page of the site, **Then** playback continues
   uninterrupted and the player remains visible with its position preserved.
4. **Given** audio is playing, **When** the reader presses pause, seeks, or changes the
   playback speed, **Then** the player responds and reflects the new state.
5. **Given** audio is playing, **When** the reader dismisses the player, **Then** playback
   stops and the player disappears.
6. **Given** an aggregated news article whose AI summary already has ready audio,
   **When** the reader opens the news feed, **Then** that article's card offers the same
   duration-labelled play control alongside its existing summary and favourite controls.
7. **Given** the reader is in the site's admin area, **When** any page loads, **Then** the
   docked player is not present.
8. **Given** an event card in the news feed, **When** the reader looks at it, **Then** it
   offers no listen control, because events are never summarised and so can never have
   audio.

---

### User Story 2 - Generate audio for an item that has none, from the listing (Priority: P2)

A reader sees an item they want to listen to but which has no audio yet. Its card offers a
lower-emphasis "Listen" invitation. Pressing it signs the reader in if they are not already
(generating audio spends from a metered monthly budget), then starts the work and hands
progress straight to the docked player, which reports what stage it is at. When the audio is
ready it starts playing on its own — pressing Listen and completing a sign-in is explicit
enough consent. Meanwhile the card itself flips to advertise the new duration, so it stays
playable even if the reader has dismissed the player.

**Why this priority**: It removes the remaining dead end — a cold card that cannot be acted
on — but depends on Story 1's player and control existing first.

**Independent Test**: With no audio present, press Listen on a blog card and on a news card,
complete sign-in, and confirm the player reports progress, auto-plays on completion, and the
card ends up showing a duration.

**Acceptance Scenarios**:

1. **Given** an item with no audio, **When** the reader views its card, **Then** the card
   shows a visible but secondary "Listen" invitation rather than hiding the control.
2. **Given** a signed-out reader presses "Listen", **When** the sign-in prompt appears and
   they complete it, **Then** generation starts.
3. **Given** a signed-out reader presses "Listen", **When** they dismiss the sign-in prompt,
   **Then** nothing happens: no work is started and no error is shown.
4. **Given** generation is running, **When** the reader looks at the player, **Then** it
   shows the item's title and a stage label describing what is happening, with no transport
   controls, and a dismiss control.
5. **Given** generation is running, **When** it completes and the reader has not dismissed
   the player, **Then** playback starts automatically.
6. **Given** generation is running, **When** the reader dismisses the player, **Then** the
   player clears and nothing auto-plays later, but the work still completes and the card
   still ends up showing the new duration without a page reload.
7. **Given** a news article that has no AI summary at all, **When** the reader presses
   "Listen", **Then** the summary is produced first and then narrated, with the player
   reporting each stage in turn, and the card's summary control updates to reflect that a
   summary now exists.
8. **Given** generation is running for one item, **When** the reader presses Listen on a
   different item, **Then** the first request is abandoned and the player switches to the
   new item.

---

### User Story 3 - Understand what went wrong without losing your place (Priority: P3)

When audio cannot be produced or played, the reader is told why in the player, in plain
language, and is offered a retry where retrying could actually help. The card returns to its
resting state rather than being left stuck mid-spinner, and the listing itself never breaks.

**Why this priority**: Necessary for the feature to be trustworthy, but the happy paths in
Stories 1 and 2 carry the value.

**Independent Test**: Force each failure condition in the table under *Error Handling* and
confirm the stated message and retryability, and that the listing still renders.

**Acceptance Scenarios**:

1. **Given** the monthly audio budget is exhausted, **When** the reader presses "Listen",
   **Then** the player reports that audio is unavailable this month and offers no retry.
2. **Given** the reader has made too many requests in a short window, **When** a request is
   rejected, **Then** the player says so using the server's own wait guidance and offers a
   retry.
3. **Given** an article has too little source text to summarise, **When** the reader presses
   "Listen", **Then** the player says there isn't enough of the article to summarise and
   offers no retry.
4. **Given** the bulk lookup of which items have ready audio fails, **When** the listing
   renders, **Then** the listing renders normally and every card shows the cold "Listen"
   invitation.
5. **Given** generation takes longer than the polling window allows, **When** polling gives
   up, **Then** the player offers a manual re-check rather than declaring failure.
6. **Given** an item's audio file has since been removed, **When** the reader presses play,
   **Then** the player reports that the audio is no longer available and clears the track.
7. **Given** any failure, **When** the reader looks at the card, **Then** the card shows its
   resting state and no error text.

---

### User Story 4 - Generating audio is a privileged action everywhere (Priority: P2)

Producing narration audio draws on a metered monthly character budget. Today a blog post's
audio can be generated by anyone, anonymously, while a news summary's audio requires
sign-in. That asymmetry becomes untenable once the listing exposes generation on every card,
so blog narration generation is brought in line: it requires sign-in from every surface,
including the existing blog detail page.

**Why this priority**: It is a prerequisite for Story 2 being safe to ship; gating only the
new listing surface would leave the same post anonymously narratable from the detail page.

**Independent Test**: Attempt to trigger blog narration generation without signing in and
confirm it is refused; repeat signed in and confirm it succeeds; confirm reading existing
audio still needs no sign-in.

**Acceptance Scenarios**:

1. **Given** a reader who is not signed in, **When** they try to generate a blog post's
   audio, **Then** the request is refused as unauthenticated.
2. **Given** a signed-in reader, **When** they generate a blog post's audio, **Then** it
   succeeds as before.
3. **Given** any reader, signed in or not, **When** they read an item's existing audio
   status, **Then** it is served without requiring sign-in, because the audio is shared
   content rather than per-reader.
4. **Given** a signed-out reader on a blog post's detail page, **When** they press the
   existing generate-audio control, **Then** they are prompted to sign in rather than shown
   an authentication failure.
5. **Given** a signed-out reader on a blog post's detail page, **When** they dismiss that
   prompt, **Then** the page reports that audio is unavailable with an invitation to sign
   in, matching how the news summary drawer already behaves.

---

### Edge Cases

- **Several stored audio renders exist for the same item.** Audio is addressed by a
  fingerprint of the content and its rendering settings, so one item can accumulate several
  rows over time. The listing must advertise only the newest usable one, and must never
  advertise a stale, failed or unverified render.
- **A listing page load must not exhaust the reader's request allowance.** Reading which
  items have audio has to cost one request for the whole page, not one per card; per-card
  polling would be rejected on first render.
- **A news card is itself a link to the original article.** Pressing the listen control
  must not also open that article.
- **Two players on one page.** A blog detail page keeps its own inline audio player, so both
  it and the docked player can exist at once; starting one must pause the other.
- **The reader navigates away mid-generation.** The listing page that started the work may
  unmount; the work and the player must survive it.
- **Narrow viewports.** The docked player must stay usable when there is no room for the
  full transport, dropping the least essential control rather than overflowing.
- **Assistive technology.** Stage changes in the player happen without any reader action, so
  they must be announced rather than only shown.

## Requirements *(mandatory)*

### Functional Requirements

#### Discovering what is listenable

- **FR-001**: The system MUST provide a single bulk lookup that, for a given content type,
  returns every item that currently has ready audio together with that audio's location and
  approximate duration.
- **FR-002**: That lookup MUST return, per item, only the most recent ready render, and MUST
  exclude renders that are stale, failed or unverified.
- **FR-003**: That lookup MUST be readable without sign-in, consistent with existing bulk
  listing-state lookups, because the audio is globally shared rather than per-reader.
- **FR-004**: That lookup MUST NOT consume the reader's narration request allowance, so that
  loading a listing page never leaves the reader unable to act on it.
- **FR-005**: For aggregated news articles, the lookup MUST be keyed by the article
  identifier the news listing already holds, requiring no additional lookup to correlate.
- **FR-006**: If the bulk lookup fails, the listing MUST still render, with every card in its
  cold state.

#### The per-card control

- **FR-007**: Cards on the blog listing (both the featured item and the grid) and on the news
  feed (both the hero card and the feed cards) MUST offer a listen control.
- **FR-008**: When an item has ready audio, its control MUST show a play affordance labelled
  with the audio's approximate duration, formatted identically to the existing detail-page
  player's duration label.
- **FR-009**: When an item has no audio, its control MUST still be visible, at secondary
  visual weight, inviting the reader to have audio generated.
- **FR-010**: While work is in flight for an item, its control MUST show a progress state with
  a stage label, and that state MUST be derived from shared state keyed on the item so that
  re-rendering the list cannot lose it.
- **FR-011**: Event cards MUST NOT offer a listen control.
- **FR-012**: On cards that are themselves links, activating the listen control MUST NOT
  trigger the card's link navigation.
- **FR-013**: A news card MUST show at most three actions: listen, summarise and favourite.

#### The docked player

- **FR-014**: The system MUST provide a single player, docked to the bottom of the viewport,
  that owns all listing-initiated playback.
- **FR-015**: That player MUST survive navigation between pages, filter changes and
  incremental loading of more items, continuing playback and preserving position.
- **FR-016**: When audio is ready, the player MUST offer the item's title as a link to the
  item, play/pause, seeking, elapsed and total time, the existing playback-speed choices, and
  a dismiss control.
- **FR-017**: While work is in flight, the player MUST show the title, a stage label, and a
  dismiss control, and MUST NOT show transport controls.
- **FR-018**: On narrow viewports the player MUST retain the title, play/pause and a progress
  indication, and MUST drop the playback-speed control.
- **FR-019**: The player MUST NOT appear in the administrative area of the site.
- **FR-020**: The player MUST be an accessible labelled region, and MUST announce stage
  changes politely to assistive technology.
- **FR-021**: Starting playback in the player MUST pause any other audio playing on the page,
  and starting the detail page's own player MUST pause the docked one.

#### The generation chain

- **FR-022**: Pressing listen on an item that already has audio MUST start playback with no
  network round trip.
- **FR-023**: Pressing listen on an item without audio MUST first require the reader to be
  signed in, and MUST do nothing at all — no request, no error — if the reader abandons
  signing in.
- **FR-024**: For a blog post without audio, the chain MUST be: sign in, request narration,
  await completion, play.
- **FR-025**: For a news article that already has a summary but no audio, the chain MUST be:
  sign in, request summary narration, await completion, play.
- **FR-026**: For a news article with no summary, the chain MUST be: sign in, produce the
  summary, then request its narration, await completion, play — with the player naming each
  stage as it happens.
- **FR-027**: When a chain the reader is still watching completes, playback MUST start
  automatically.
- **FR-028**: Starting a chain for a different item MUST abandon the one in flight.
- **FR-029**: Awaiting completion MUST reuse the existing narration polling policy rather
  than introducing a second one.
- **FR-030**: When a chain completes, the newly available audio MUST be published to the
  shared ready-audio state so that the item's card advertises its duration, whether or not
  the player is still showing that item.
- **FR-031**: When a chain produces a news summary as an intermediate step, the news
  listing's record of which articles have summaries MUST be updated in place, so the card's
  summary control reflects reality without refetching the whole set.
- **FR-032**: Dismissing the player mid-chain MUST stop watching and clear the player, MUST
  NOT cancel the work already started, and MUST NOT auto-play when that work lands.

#### Authorisation

- **FR-033**: Requesting generation of a blog post's narration MUST require an authenticated
  reader, matching the existing requirement for news summary narration.
- **FR-034**: Reading narration state MUST remain available without sign-in for both blog
  posts and news summaries.
- **FR-035**: The blog post detail page MUST prompt an unauthenticated reader to sign in
  before requesting narration, and MUST report audio as unavailable with a sign-in
  invitation if the prompt is abandoned.
- **FR-036**: Documentation that records the previous public-generation behaviour as
  deliberate MUST be corrected to record why it changed.

#### Error handling

- **FR-037**: All failures MUST surface in the player and MUST NOT surface on the card; the
  card MUST return to its resting state.
- **FR-038**: The system MUST distinguish retryable from non-retryable failures and MUST
  offer a retry control only for the former.
- **FR-039**: An exhausted monthly audio budget MUST be reported as unavailable this month
  and MUST NOT be retryable.
- **FR-040**: An article with insufficient source text MUST be reported as not having enough
  content to summarise and MUST NOT be retryable.
- **FR-041**: A rejected request due to rate limiting MUST be reported as retryable using the
  server's own wait guidance.
- **FR-042**: Exhausting the polling window MUST offer a manual re-check rather than being
  reported as a failure.
- **FR-043**: Audio that cannot be fetched at playback time MUST be reported as no longer
  available, and the player MUST clear the track.

### Key Entities

- **Ready-audio entry**: The advertised fact that one item is listenable now — the item's
  identifier, where its audio lives, and roughly how long it is. Derived from stored
  narration records; only the newest ready record per item is ever advertised.
- **Track**: What the player is currently on — the item's content type and identifier, its
  title, a link to it, and, once known, its audio location and duration.
- **Chain stage**: Where a generation request has got to — idle, summarising, narrating, or
  ready — together with whether audio is playing, the playback position and speed, and any
  error and whether that error is worth retrying.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A reader can start listening to an item that already has audio from a listing
  page in one action, with no intermediate loading state.
- **SC-002**: Audio started from a listing page keeps playing across at least one filter
  change, one "load more", and one navigation to a different page of the site, with no
  audible gap and no loss of position.
- **SC-003**: Every blog listing card and every news article card presents a listen control,
  so the proportion of listenable-in-principle items with no visible listen affordance is
  zero.
- **SC-004**: A reader who presses listen on an item with no audio reaches playback without
  visiting that item's own page, and without having to re-find the item afterwards.
- **SC-005**: Loading a listing page costs exactly one request to learn which of its items are
  listenable, regardless of how many items are shown.
- **SC-006**: Loading a listing page leaves the reader's narration request allowance intact,
  so pressing listen immediately afterwards is never rejected as too many requests.
- **SC-007**: Narration audio generation cannot be triggered by an unauthenticated reader
  from any surface of the site.
- **SC-008**: Every failure condition enumerated in the Error Handling requirements produces
  a distinct, plain-language message in the player, and the listing remains fully usable in
  all of them.
- **SC-009**: Dismissing the player during generation still results in the item's card
  advertising its new duration once the work completes, with no page reload.
- **SC-010**: The docked player is operable by keyboard alone and announces each stage change
  to assistive technology.

## Assumptions

- The existing narration and summary generation capabilities, their storage, their status
  model and their polling behaviour are reused unchanged; this feature adds a way to reach
  them and a place to play their output, not a new way to produce audio.
- The existing detail-page audio player and news summary drawer keep their current structure
  and behaviour, except for the sign-in prompt that the authorisation change requires.
- Audio is globally shared across readers rather than personalised, so knowing which items
  have audio is public information, and the same generated file serves everybody.
- Auto-playing on completion is acceptable because it only ever follows a deliberate press
  and, for cold items, a completed sign-in — it is never unsolicited.
- Sampling from a listing is the use case, so there is no queue and no auto-advance to the
  next item.
- Approximate durations are sufficient for the card label; exact lengths are not required.
- The blog listing's grid card is shared with the home page's featured-writing section, which
  therefore inherits the listen control deliberately.
- Apparently-unused blog card components are left alone; removing them is not part of this
  change.

## Out of Scope

- Auto-advance and queueing of multiple items.
- Replacing the blog detail page's inline player with the docked player.
- Narrating a third party's full article body rather than our summary of it.
- Audio for events.
- Removing apparently-dead blog card components.
