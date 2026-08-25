# Feature Specification: On-demand article summaries with audio

**Feature Branch**: `simonrowe/article-ai-summary-audio` (feature id `034-article-summary-audio`)

**Created**: 2026-08-24

**Status**: Draft

**Input**: User description: "Implement the design in docs/superpowers/specs/2026-08-24-article-summary-audio-design.md: on-demand, globally-shared, AI-generated in-depth summaries for aggregated news articles, shown in a right-side drawer, with on-demand audio narration of that summary. Includes generalising the existing blog narration pipeline to arbitrary content types."

## Overview

Aggregated news articles on the News & Events page currently show a one-paragraph
blurb written at ingest, plus a link out to the original. Long, substantive articles
are poorly served by that blurb.

This feature lets a signed-in visitor ask for an in-depth, multi-paragraph summary of
any aggregated news article, read it in a side panel without losing their place on the
page, and optionally listen to a spoken narration of that summary. Summaries and audio
are generated once and shared with every subsequent visitor, signed in or not.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Request an in-depth summary of a news article (Priority: P1)

A signed-in visitor browsing the news list finds an article that looks interesting but
does not want to leave the site to read the whole thing. They choose **Summarise** on
the card. A side panel opens, tells them the summary is being written, and within about
half a minute shows several paragraphs describing what the article actually says. The
panel clearly labels the text as AI-generated. Closing the panel returns them to the
list with their filters, page position and favourites untouched.

**Why this priority**: This is the feature. Without it there is nothing to read, listen
to, or share. It is independently valuable even if audio is never added.

**Independent Test**: Sign in, open the news list, choose **Summarise** on an article
with substantial source text, and confirm a multi-paragraph summary appears in the side
panel, marked as AI-generated, with the page state behind it preserved.

**Acceptance Scenarios**:

1. **Given** a signed-in visitor on the news list and an article with no existing
   summary, **When** they choose **Summarise**, **Then** the side panel opens showing a
   generating state and then replaces it with a 4–6 paragraph prose summary.
2. **Given** the summary panel is open, **When** the visitor presses Escape, clicks
   outside the panel, or uses the close control, **Then** the panel closes and the news
   list is exactly as they left it.
3. **Given** the summary panel is open, **When** the visitor reads the panel header,
   **Then** they can see the source name, publication date, the article title linking
   to the original, a link to read the original, and a clear label stating the summary
   was written by AI.
4. **Given** a signed-out visitor, **When** they choose **Summarise**, **Then** they are
   prompted to sign in, and the summary is only requested once a session is actually
   established.
5. **Given** a signed-out visitor who dismisses the sign-in prompt, **When** the prompt
   closes, **Then** no summary is requested and no cost is incurred.
6. **Given** an article that already has a summary, **When** any visitor (signed in or
   out) chooses **Read summary**, **Then** the panel opens with the existing summary
   immediately and nothing new is generated.

---

### User Story 2 - Listen to the summary (Priority: P2)

Having opened a summary, a signed-in visitor wants to listen rather than read. They
choose to generate audio; a short while later a player appears and they can play,
pause, and change playback speed. Closing the panel stops playback. A later visitor
opening the same article's summary gets the audio immediately.

**Why this priority**: Valuable, but strictly additive — the summary is useful without
it, and audio depends on the summary existing first.

**Independent Test**: With a ready summary open, request audio, wait for it to render,
and confirm playback works and stops when the panel closes.

**Acceptance Scenarios**:

1. **Given** a ready summary and a signed-in visitor, **When** they request audio,
   **Then** the panel shows progress and eventually presents a working audio player.
2. **Given** audio is playing, **When** the visitor closes the panel, **Then** playback
   stops.
3. **Given** an article whose summary audio already exists, **When** any visitor opens
   the summary, **Then** the player is available immediately with no new generation.
4. **Given** a signed-out visitor viewing an existing summary with no audio, **When**
   they request audio, **Then** they are prompted to sign in first.
5. **Given** text-to-speech is switched off or unconfigured in the environment, **When**
   a visitor opens a summary, **Then** the panel reports audio as temporarily
   unavailable and the written summary is unaffected.

---

### User Story 3 - See at a glance which articles already have summaries (Priority: P2)

A visitor scanning the news list can tell, before clicking anything, which articles
already have a summary waiting. Those cards offer **Read summary**; the rest offer
**Summarise**. This works whether or not the visitor is signed in.

**Why this priority**: It is what makes shared summaries visible and worth having, and
it prevents signed-out visitors being pushed into a sign-in prompt for content that is
already free to read.

**Independent Test**: With summaries existing for some articles and not others, load the
news list signed out and confirm the two button labels appear on the correct cards.

**Acceptance Scenarios**:

1. **Given** a mix of articles with and without summaries, **When** the news list loads,
   **Then** cards with a ready summary show **Read summary** and the rest show
   **Summarise**.
2. **Given** a signed-out visitor, **When** they choose **Read summary**, **Then** the
   panel opens with no sign-in prompt.
3. **Given** an event (not a news article), **When** its card renders, **Then** no
   summary control is shown.

---

### User Story 4 - Two people ask for the same summary at the same time (Priority: P3)

Two visitors request a summary of the same article within seconds of each other. Exactly
one summary is written. The second visitor sees a generating state and then the same
summary the first visitor gets.

**Why this priority**: Correctness and cost control rather than a visible feature, but
it must hold from day one because every duplicate generation costs money.

**Independent Test**: Issue two concurrent summary requests for one article and confirm
only one generation occurs and both callers converge on the same result.

**Acceptance Scenarios**:

1. **Given** no existing summary, **When** two requests for the same article arrive
   concurrently, **Then** exactly one summary is generated and both requesters end up
   viewing it.
2. **Given** a summary generation that never completed because the process stopped,
   **When** a visitor requests that summary after the configured timeout has passed,
   **Then** generation is retried.
3. **Given** a summary generation that started moments ago, **When** another visitor
   requests the same summary, **Then** generation is not restarted.

---

### Edge Cases

- **Source text too thin.** Some aggregated articles carry only a feed snippet, and
  some sources return a consent or paywall interstitial instead of the body. When the
  best available source text falls below the usable floor, the panel states plainly
  that there is not enough of the article to summarise, and offers the original link.
  This outcome is remembered so repeat requests do not re-spend.
- **Model failure.** If summary generation fails for a transient reason, the panel says
  so and the visitor can try again.
- **Article missing or hidden.** If the article no longer exists or is not visible, the
  request fails with a clear message and is not retried.
- **Summary regenerated after a prompt change.** Existing audio for the previous text
  is no longer offered as current; new audio is generated on request.
- **Rapid repeat requests from one visitor.** Requests are rate limited; a visitor who
  exceeds the limit is told to wait rather than silently costing money.
- **Panel opened for an article whose summary is mid-generation elsewhere.** The panel
  shows the generating state and updates automatically when it completes, without the
  visitor refreshing.
- **Existing blog narration.** Generalising the narration pipeline must leave blog
  audio, its URLs and its existing generated files working exactly as before.

## Requirements *(mandatory)*

### Functional Requirements

**Summaries**

- **FR-001**: The system MUST let an authenticated visitor request an in-depth summary
  of any visible aggregated news article.
- **FR-002**: The system MUST allow anyone, authenticated or not, to read a summary that
  already exists.
- **FR-003**: The system MUST generate at most one summary per article per summary
  format version, shared globally across all visitors.
- **FR-004**: The system MUST produce summaries as 4–6 paragraphs of neutral,
  third-person prose that convey what the article says, without restating the title and
  without a heading.
- **FR-005**: The system MUST reuse an existing completed summary without regenerating
  it or incurring further cost.
- **FR-006**: The system MUST ensure that concurrent requests for the same article's
  summary result in exactly one generation, with all other requesters observing the
  in-progress state.
- **FR-007**: The system MUST recover from a summary generation that was interrupted, by
  allowing a fresh attempt once a configured timeout has elapsed, while preventing two
  simultaneous recovery attempts.
- **FR-008**: The system MUST derive summary source text from the article using, in
  order of preference, a fresh fetch of the original page, the stored article content,
  and the stored blurb — rejecting content below a usable-length floor as likely to be a
  consent or paywall interstitial.
- **FR-009**: The system MUST record a non-retryable failure, and surface a plain
  explanation, when the best available source text falls below the hard minimum length,
  and MUST NOT call the model in that case.
- **FR-010**: The system MUST record failures on the summary itself, distinguishing
  retryable causes (model error) from non-retryable ones (insufficient source text,
  article not found), so repeated requests do not silently re-spend.
- **FR-011**: The system MUST expose the set of articles that have a completed summary,
  readable without authentication.
- **FR-012**: The system MUST allow a client to wait for a summary's state to change and
  be notified promptly when it does, without repeated rapid polling.
- **FR-013**: The system MUST invalidate all existing summaries when the summary format
  version changes, so a prompt change never serves stale text.
- **FR-014**: The system MUST rate limit summary *generation* requests per configured
  requests-per-minute, and MUST NOT meter the status reads against that allowance — a
  single reader waiting for one summary issues several status reads and must not be turned
  away mid-wait.

**Audio**

- **FR-015**: The system MUST let an authenticated visitor request spoken audio of a
  completed summary.
- **FR-016**: The system MUST allow anyone to play summary audio that already exists.
- **FR-017**: The system MUST support narration for both blog posts and article
  summaries through one shared pipeline, identified by content type and content id.
- **FR-018**: The system MUST preserve the existing blog narration endpoint path,
  request/response contract and previously generated blog audio unchanged.
- **FR-019**: The system MUST migrate existing narration records to the generalised
  content-type/content-id shape, idempotently.
- **FR-020**: The system MUST mark previously generated audio as no longer current when
  the summary text it was generated from changes.
- **FR-021**: The system MUST report audio as unavailable, without breaking the written
  summary, when text-to-speech is disabled or unconfigured.
- **FR-022**: The system MUST continue to enforce the existing monthly character budget
  for speech synthesis, and MUST require authentication for audio generation so an
  anonymous caller cannot drain it.

**Presentation**

- **FR-023**: The system MUST present summaries in a right-side panel over the news list,
  preserving the underlying page's filters, paging, scroll position and favourites state.
- **FR-024**: The panel MUST close on Escape, on a click outside it, and via an explicit
  close control, and MUST prevent background scrolling while open.
- **FR-025**: The panel MUST show, in order: source name and publication date; the
  article title linking to the original; a prominent label disclosing that the summary
  is AI-generated; the summary prose; the audio panel; a link to the original; and the
  favourite control.
- **FR-026**: Closing the panel MUST stop any audio playback.
- **FR-027**: Each news card MUST show a summary control beside the favourite control,
  labelled **Read summary** when a summary exists and **Summarise** when it does not.
- **FR-028**: Choosing **Summarise** while signed out MUST prompt sign-in first, and MUST
  NOT issue the request unless a session is genuinely established (a dismissed prompt
  must not be treated as success).
- **FR-029**: Summary controls MUST NOT appear on event cards.
- **FR-030**: Summary prose MUST be rendered with the same restricted link and image
  policy applied to other model-generated content on the site, with no raw HTML.
- **FR-031**: The panel MUST show distinct, understandable states for generating,
  ready, insufficient-source, and failed.

### Key Entities

- **Article Summary**: The generated in-depth summary of one aggregated news article.
  Identified deterministically from the article and the summary format version. Carries
  its state (generating, ready, failed), a version counter that advances on every change
  so clients can wait for updates, the summary prose, the model used, the amount of
  source text consumed, request/completion/update timestamps, and — on failure — a
  failure code and whether retrying is worthwhile.
- **Narration**: The existing spoken-audio artefact, generalised from "belongs to a blog"
  to "belongs to a content item of a given type". Retains its existing state machine,
  audio file location, text fingerprint and staleness behaviour.
- **Narration Source**: The rule for a given content type describing how to obtain the
  text to narrate and whether an existing narration still matches the current text.
- **Aggregated Article**: Existing entity. Supplies title, source, publication date,
  original link, stored content and blurb. Unchanged by this feature.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A visitor can go from seeing an article in the list to reading a complete
  in-depth summary without leaving the page, in under 45 seconds for a first-time
  summary and under 2 seconds for one that already exists.
- **SC-002**: An article is never summarised more than once per summary format version,
  including under concurrent requests — verified by exactly one model call across two
  simultaneous requests.
- **SC-003**: 100% of summaries displayed carry a visible AI-generated disclosure before
  the prose.
- **SC-004**: Closing the panel restores the news list with filters, page and scroll
  position unchanged in 100% of cases, and stops audio playback immediately.
- **SC-005**: Articles with too little source text produce a clear explanatory message
  rather than an invented summary, in 100% of such cases, with zero model calls.
- **SC-006**: All existing blog narration behaviour and tests continue to pass unchanged
  after the pipeline is generalised, with no change to blog-side URLs or previously
  generated audio.
- **SC-007**: A signed-out visitor can read every existing summary and play every
  existing summary audio without signing in, and is prompted to sign in only when their
  action would create something new.
- **SC-008**: Summary generation failures are distinguishable by the reader as either
  "worth trying again" or "cannot be summarised", with no silent repeat spend on the
  latter.

## Assumptions

- Summaries are generated from third-party article text and shared globally; there is no
  per-visitor personalisation of summary content.
- Any valid signed-in session is sufficient to request generation; no elevated or admin
  role is required, matching the existing favourites behaviour.
- Summary generation completes within roughly 15–30 seconds and is performed while the
  request is held open, unlike audio which is inherently long-running.
- Audio narration is only offered for the generated summary, never for the full original
  article text; the design leaves room for that to be added later.
- Events are excluded; only aggregated news articles are summarised.
- Text-to-speech must be enabled and configured in the target environment for audio to
  work; when it is not, summaries still function and audio reports unavailable.
- The site's existing sign-in mechanism, favourites behaviour, side-panel styling, and
  restricted markdown rendering policy are reused rather than replaced.
- Existing blog narration data must be migrated in place; regenerating existing blog
  audio is not acceptable.
