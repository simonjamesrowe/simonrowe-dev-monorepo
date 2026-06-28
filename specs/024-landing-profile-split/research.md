# Research: Landing Profile Split

## Decision: Reuse current homepage profile background

**Rationale**: The user explicitly prefers the current background photo and asked for the mockup influence without losing that visual anchor.

**Alternatives considered**: Replacing the background with the mockup's ambient treatment was rejected because it removes the existing personal/photo signal the user likes.

## Decision: Move profile and contact to a real `/profile` route

**Rationale**: The homepage must stop after chat, while profile/contact still need a public destination. A route already has a `ProfilePage` component, so wiring it into React Router is the smallest change.

**Alternatives considered**: Keeping hidden homepage anchors was rejected because it preserves the old page structure and leaves navigation/tour selectors tied to removed content.

## Decision: Remove homepage contact drawer orchestration

**Rationale**: Contact belongs on Profile after the split. Keeping the homepage drawer would duplicate contact entry points and keep tour actions coupled to a removed CTA.

**Alternatives considered**: Opening the Profile contact section in a drawer was rejected because the user asked for contact on the same page as the profile.

## Decision: Seed/update deterministic tour steps instead of relying on backup data

**Rationale**: Restored or legacy tour records can point to old selectors. Deterministic seed data gives the public tour a stable default that matches the redesigned DOM.

**Alternatives considered**: Leaving tour management entirely to admin UI was rejected because the user specifically requested updating tour seeding and missing selectors would break the public guided tour.

## Decision: Keep styling in `frontend/src/styles.css`

**Rationale**: The constitution requires plain CSS with BEM naming in a single stylesheet. The existing home/profile/nav styles already live there.

**Alternatives considered**: CSS modules or a utility framework were rejected because they violate project conventions and add unnecessary surface area.
