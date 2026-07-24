# Feature Specification: Favourite News & Events

**Feature Branch**: `029-favourite-news-events`

**Created**: 2026-07-24

**Status**: Draft

**Input**: User description: "Allow the authenticated owner to save News articles and Events as favourites, then filter each listing to show only saved items. Favourites are stored server-side per user (Auth0 identity) so they follow the owner across devices." (Design doc: `docs/superpowers/specs/2026-07-23-favourite-news-events-design.md`)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Save and unsave a news article or event (Priority: P1)

As the site owner browsing the News or Events page while logged in, I can mark any article or event as a favourite with a single click on a heart icon on its card, and unmark it the same way. The heart fills in immediately to confirm the save.

**Why this priority**: This is the core capability — without the ability to save items, no other part of the feature has value.

**Independent Test**: Log in, click the heart on a news card, see it fill; reload the page and the heart is still filled; click again and it empties. Delivers persistent bookmarking on its own.

**Acceptance Scenarios**:

1. **Given** I am logged in and viewing the News page, **When** I click the empty heart on an article card, **Then** the heart fills immediately and the article is saved to my favourites.
2. **Given** I have favourited an article, **When** I reload the page or open the site on a different device and log in, **Then** the heart on that article is shown filled.
3. **Given** I have favourited an event, **When** I click its filled heart, **Then** the heart empties and the event is removed from my favourites.
4. **Given** I favourite the same item twice (e.g. rapid double-click), **Then** only one favourite exists and no error is shown.

---

### User Story 2 - View favourites only (Priority: P2)

As the logged-in owner, I can flip a "Show favourites only" toggle in the News or Events page header to see just my saved items for that page, most recently saved first, and flip it back to return to the full listing.

**Why this priority**: Filtering is the payoff for saving — it turns favourites into a usable reading/planning list — but depends on Story 1 existing.

**Independent Test**: With at least one favourite saved, switch the toggle on and confirm only saved items appear in saved order; switch it off and the full listing returns.

**Acceptance Scenarios**:

1. **Given** I am logged in with three favourited articles, **When** I enable "Show favourites only" on the News page, **Then** only those three articles are shown, ordered by most recently favourited first.
2. **Given** favourites-only mode is on, **When** I disable the toggle, **Then** the normal full listing is restored.
3. **Given** I favourited an item that has since been hidden from the public listing, **When** I view favourites only, **Then** the item still appears (it is my private list).
4. **Given** I favourited an item whose underlying content has since been deleted, **When** I view favourites only, **Then** that item is silently skipped and the rest of my favourites display normally.
5. **Given** I have no favourites for the current page type, **When** I enable the toggle, **Then** I see an empty state rather than an error.

---

### User Story 3 - Login prompt with seamless completion (Priority: P3)

As a logged-out visitor (the owner before signing in), when I click a heart or the favourites toggle, I am prompted to log in via a popup — the page underneath never navigates away — and once I complete login, my intended action (the save, or showing favourites) completes automatically.

**Why this priority**: Removes friction for the owner arriving logged out; the feature is still usable without it by logging in first via the admin area.

**Independent Test**: While logged out, click a heart; complete login in the popup; observe the heart fills without any further clicks and the page never reloaded.

**Acceptance Scenarios**:

1. **Given** I am logged out on the News page, **When** I click a heart, **Then** a login popup opens and the page behind it does not navigate away.
2. **Given** the login popup is open after clicking a heart, **When** I complete login successfully, **Then** the save completes automatically and the heart fills.
3. **Given** the login popup is open, **When** I dismiss or cancel it, **Then** nothing is saved and the page remains usable.
4. **Given** I am logged out, **When** I click "Show favourites only", **Then** the same popup flow runs and on success the favourites view loads.
5. **Given** I am logged out, **When** I view the News or Events page, **Then** hearts render empty (no favourite state is shown or fetched).

---

### Edge Cases

- Favouriting an item that no longer exists on the server (stale page) → the user sees the action fail gracefully; no favourite is recorded (server responds "not found").
- Removing a favourite that was already removed (e.g. from another device) → succeeds silently; no error.
- A request with an invalid content type (anything other than news/events) is rejected as a bad request.
- Unauthenticated requests to any favourites capability are rejected (unauthorised); no favourites data leaks.
- Two different users' favourites are fully isolated — one user can never see or affect another's list.
- Referenced content deleted after favouriting → skipped in the favourites listing without error (see Story 2, scenario 4).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Authenticated users MUST be able to mark any news article or event as a favourite, and unmark it, from its card on the listing page.
- **FR-002**: Saving a favourite MUST be idempotent — repeated saves of the same item by the same user result in exactly one favourite record.
- **FR-003**: Removing a favourite MUST be idempotent — removing an item that is not favourited succeeds without error.
- **FR-004**: Favourites MUST be stored server-side, keyed to the user's identity, so they persist across sessions and devices.
- **FR-005**: All favourites operations MUST require authentication; every read and write MUST be scoped to the calling user's own identity.
- **FR-006**: Favouriting an item that does not exist MUST be rejected with a "not found" outcome.
- **FR-007**: Requests naming a content type other than news or events MUST be rejected as invalid.
- **FR-008**: The News and Events pages MUST show each item's current favourite state (filled vs empty heart) for the logged-in user, and empty hearts when logged out.
- **FR-009**: Each page (News, Events) MUST offer a "Show favourites only" toggle that replaces the listing with only the user's favourited items of that type, ordered by most recently favourited first, and restores the full listing when turned off.
- **FR-010**: The favourites-only listing MUST include favourited items regardless of their public visibility flag, and MUST silently skip favourites whose referenced content no longer exists. Public listings keep their existing visibility filtering.
- **FR-011**: When a logged-out visitor attempts to favourite an item or enable the favourites toggle, the system MUST prompt for login in a popup without navigating away from the page, and on successful login MUST automatically complete the originally intended action.
- **FR-012**: Cancelling or failing the login popup MUST leave the page usable with no favourite recorded.
- **FR-013**: The favourite action MUST reflect immediately in the UI (optimistic update) when clicked by a logged-in user.

### Key Entities

- **Favourite**: A record that a specific user saved a specific piece of content. Attributes: owning user identity, content type (news or event), reference to the content item, time saved. Unique per (user, type, content item).
- **News article / Event (existing)**: The aggregated content items being favourited. Unchanged by this feature; favourites only reference them.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A logged-in user can save or unsave an item with a single click, with visual confirmation appearing in under 1 second.
- **SC-002**: Favourites saved on one device appear on another device after login, with no user action beyond logging in.
- **SC-003**: 100% of favourites operations by one user are invisible to and unaffected by any other user.
- **SC-004**: A logged-out visitor who clicks a heart and completes login gets their save completed with zero additional clicks and zero page reloads.
- **SC-005**: The favourites-only view shows exactly the user's saved items of that type, newest-saved first, including items no longer publicly visible, and never errors on deleted content.

## Assumptions

- The only expected authenticated user today is the site owner (admin), but the feature is gated on authentication generally, not the admin role — any valid logged-in identity gets its own private favourites.
- No dedicated combined "favourites page" is in scope; favourites are viewed inline on the existing News and Events pages.
- No changes to how news/events are aggregated, stored, or publicly displayed.
- No public sign-up flow is added; the login prompt reuses the existing identity provider and its already-registered callback, requiring no identity-provider configuration changes.
- No data migration is needed; favourites storage is created on first use.
- Standard page sizes apply to the favourites-only listing (same pagination behaviour as the existing listings).
