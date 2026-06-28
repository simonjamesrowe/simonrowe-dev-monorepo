# Landing Profile Split Design

## Summary

Update the public site so the landing page becomes a centered, chat-first hero based on the approved v4 mockup. The page keeps the current background photo and a full-width top banner, reduces the oversized mockup typography, and stops after the chat hero before the footer. Profile, biography, CV/social links, and contact move to a dedicated public Profile page.

## Goals

- Make the homepage feel closer to the supplied `Landing Page.html` direction without losing the current photographic background.
- Center the landing hero content and chat entry.
- Make the top navigation/banner full width while keeping its content constrained.
- Remove homepage content after the chat hero, including About and CTA/contact drawer sections.
- Add a public `/profile` route and expose it in desktop and mobile navigation.
- Put profile content and contact form/details on the same Profile page.
- Update public tour steps and seeded tour data so the tour targets the new page structure.

## Non-Goals

- No change to chat transport, streaming behavior, or widget rendering.
- No admin CMS redesign.
- No new routing framework, animation library, or component library.
- No changes to backup/restore mechanics.

## User Experience

### Homepage

The homepage uses the existing profile background image as the first-viewport visual anchor. A full-width translucent top banner contains the brand, public navigation, search, theme controls, Ask AI, and admin link using the existing nav behavior.

The hero content is centered in the viewport:

- Eyebrow: `Engineering Leadership // AI-Native Systems`
- H1: `Simon Rowe`, with `Rowe` accented
- Role from profile data
- Short headline/tagline from profile data
- Chat composer and prompt chips as the primary interaction
- Small hint explaining that the AI knows Simon's work, stack, and career

The homepage ends after this hero section and then renders the footer. There is no About section, CTA section, or contact drawer on the homepage.

### Profile Page

The public Profile page is reachable at `/profile`. It contains:

- Profile portrait and full biography
- Core skills / professional summary content already present in profile components
- CV download and social links
- Contact section on the same page, with contact details and the contact form

The Profile page should feel consistent with the current site, not like a marketing landing page. Its purpose is reading, contact, and profile exploration.

### Navigation

Desktop and mobile public navigation should use `Profile` instead of `About`. The Profile item routes to `/profile`. The homepage no longer uses `/#about` as a primary navigation target.

Footer Explore links should include Profile at `/profile`. Footer Contact links should route to `/profile#contact`.

## Tour Updates

The existing tour must be retargeted so every selector exists after the page split.

Required tour flow:

1. Home hero/chat composer: target `.tour-home-chat`
2. Site search: target `.tour-search`
3. Ask AI nav action: target `.top-nav__ask-ai`
4. Profile page overview: target `.tour-profile`
5. Contact section on Profile page: target `.tour-contact`
6. Experience page: target `.tour-experience`
7. Blog page: target `.tour-blogs`
8. News & Events page: target `.tour-news-events`

Tour data should seed or update existing `tourSteps` records with the new selectors/routes. The old homepage About target and contact drawer action should be removed from seeded defaults. Frontend tour actions should no longer click the old CTA button or close the contact drawer for `.tour-contact`.

## Implementation Notes

### Frontend

- `frontend/src/pages/HomePage.tsx`
  - Keep profile loading/error behavior.
  - Render only `HeroSection`.
  - Remove homepage contact drawer state and CTA/About composition.

- `frontend/src/components/home/HeroSection.tsx`
  - Keep chat composer and prompt chips.
  - Add `.tour-home-chat` to the chat-first hero area.
  - Continue using `backgroundImageUrl` from profile data.
  - Remove CV and social actions from the homepage hero; Profile owns CV and social links.

- `frontend/src/pages/ProfilePage.tsx`
  - Ensure the page has `.tour-profile`.
  - Ensure contact area has `.tour-contact` and `id="contact"`.
  - Combine `BioSection`, social/CV affordances, contact details, and contact form into one cohesive page.

- `frontend/src/App.tsx`
  - Add public route `/profile` for `ProfilePage`.

- `frontend/src/components/layout/TopNav.tsx`
  - Replace About navigation with Profile.

- `frontend/src/components/layout/MobileMenu.tsx`
  - Replace About navigation with Profile.

- `frontend/src/components/layout/Footer.tsx`
  - Replace About footer path with `/profile`.
  - Replace Contact footer path with `/profile#contact`.

- `frontend/src/components/tour/tourActions.ts`
  - Remove old `.tour-contact` drawer click/cleanup actions.
  - Keep search, Ask AI, and experience drawer actions.

- `frontend/src/styles.css`
  - Update landing hero styles for centered content, current background image treatment, full-width banner compatibility, responsive behavior, and reduced typography.
  - Update Profile page styling so biography and contact appear as one cohesive profile/contact page rather than a drawer-driven homepage CTA.

### Backend

- `backend/src/main/java/com/simonrowe/migration/DataMigrationService.java`
  - Add or update deterministic tour seed data for the new public tour flow.
  - Ensure seeded records use current selectors/routes.

- Tour admin APIs do not need schema changes.

## Testing

### Frontend Tests

- Update `frontend/tests/pages/HomePage.test.tsx`:
  - Homepage renders hero identity, chat input, prompt chips, and no About/CTA/contact drawer content.
  - Prompt chips and composer still call `openChat`.
  - Navigation exposes Profile at `/profile`.

- Add/update route test in `frontend/tests/App.test.tsx`:
  - `/profile` renders the Profile page route.

- Add/update Profile page test:
  - Profile page renders biography and contact form on the same page.
  - Contact section has `id="contact"` and `.tour-contact`.

- Update tour tests if they assert old `.tour-contact` drawer behavior.

### Backend Tests

- Add/update tests around tour seed data:
  - Seeded tour records include `.tour-home-chat`, `.tour-profile`, and `.tour-contact`.
  - Seeded tour records no longer reference the old homepage About selector or contact drawer flow.

### Manual Verification

- `npm test -- HomePage`
- `npm test -- App`
- Relevant tour component tests
- Relevant backend tour/migration tests
- Browser verification at `http://localhost:5173/`:
  - Home content is centered.
  - Top banner spans full width.
  - Background photo is visible.
  - No content appears between hero/chat and footer.
  - `/profile` loads biography and contact.
  - Tour can step through the new selectors without missing targets.

## Open Decisions Resolved

- Navigation label: `Profile`
- Homepage content after chat: none, footer follows
- Background: retain current profile background photo
- Hero alignment: centered
- Top banner: full width
