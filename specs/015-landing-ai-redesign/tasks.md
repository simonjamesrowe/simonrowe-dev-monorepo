# Tasks: Landing Page AI Redesign

**Input**: Design documents from `/specs/015-landing-ai-redesign/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: No project initialization needed — this is a frontend-only feature in an existing codebase. Skip to foundational.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Create the shared ChatContext that both US1 (hero redesign) and US2 (Ask AI button) depend on. Move ChatPanel and RecaptchaGate rendering to layout level.

**CRITICAL**: No user story work can begin until this phase is complete.

- [x] T001 Create ChatContext provider with shared chat state (chatOpen, chatQuery, recaptchaVerified, showRecaptcha) and actions (openChat, closeChat, handleRecaptchaVerified, cancelRecaptcha) in `frontend/src/contexts/ChatContext.tsx`
- [x] T002 Wrap PublicLayout with ChatProvider in `frontend/src/App.tsx` and move ChatPanel + RecaptchaGate rendering from HomePage into PublicLayout, consuming state from ChatContext
- [x] T003 Refactor `frontend/src/pages/HomePage.tsx` to remove local chat state (chatOpen, chatQuery, recaptchaVerified, showRecaptcha useState hooks and handler functions) and consume ChatContext instead via useChat hook

**Checkpoint**: ChatContext is available to all public pages. ChatPanel and RecaptchaGate render at the layout level. Homepage still functions identically to before (no visual changes yet).

---

## Phase 3: User Story 1 - Centered Hero with Prominent AI Chat (Priority: P1)

**Goal**: Transform the hero section from a two-column grid layout to a single-column centered layout with the AI chat input as the primary focal point.

**Independent Test**: Load the homepage and verify the hero displays a centered headline, subtitle, chat input bar, suggested prompts, and social/CV links in a single vertical column. Chat flow (prompt click → reCAPTCHA → ChatPanel) works as before.

### Implementation for User Story 1

- [x] T004 [US1] Restructure `frontend/src/components/home/HeroSection.tsx` from two-column grid (hero__left + hero__right) to a single centered column layout: headline → subtitle → chat input bar → suggested prompt chips → social links/CV download. Remove the hero__chat-teaser wrapper and inline the chat input elements into the main column flow.
- [x] T005 [US1] Update hero CSS in `frontend/src/styles.css`: replace `.hero__grid { grid-template-columns: 7fr 5fr }` with single-column centered flex layout, center-align `.hero__name`, `.hero__tagline`, chat input, and prompt chips. Update `.hero__chat-teaser*` styles to be full-width centered input bar style. Reposition `.hero__actions` and `.hero__social` below prompt chips. Ensure the hero background gradient/image treatment is preserved.
- [x] T006 [US1] Update hero mobile responsive styles in `frontend/src/styles.css`: ensure the chat input is full-width, prompt chips wrap gracefully, and social links stack appropriately at viewports from 320px to 768px. Remove the now-unnecessary `@media (max-width: 768px) { .hero__grid { grid-template-columns: 1fr } }` rule since the layout is already single-column.
- [x] T007 [US1] Update `frontend/src/components/home/HeroSection.tsx` to consume ChatContext via useChat hook for the onChatOpen callback instead of receiving it as a prop from HomePage.

**Checkpoint**: Homepage hero displays the new centered single-column layout with AI chat input as the primary CTA. Suggested prompts and social links are visible below. Chat flow works end-to-end.

---

## Phase 4: User Story 2 - "Ask AI" Button in Top Navigation (Priority: P1)

**Goal**: Add a prominent "Ask AI" pill button to the top navigation bar, enabling AI chat from any page.

**Independent Test**: Navigate to any page (Experience, Blog, Blog Detail), click the "Ask AI" button in the top nav, verify reCAPTCHA gate appears (if not verified), and confirm chat panel opens after verification.

### Implementation for User Story 2

- [x] T008 [US2] Add "Ask AI" pill button to `frontend/src/components/layout/TopNav.tsx`: import MessageSquare from lucide-react, add a button element with `top-nav__ask-ai` class before the SiteSearch component in the actions area. Wire the button's onClick to call openChat() from ChatContext.
- [x] T009 [US2] Add "Ask AI" button CSS styles in `frontend/src/styles.css`: create `.top-nav__ask-ai` styles for a pill-shaped outline button with border, border-radius, icon + "ASK AI" text label, hover/focus states, and transitions. Style should be visually distinct from other nav elements (outlined pill with accent color).
- [x] T010 [US2] Add mobile responsive styles for the "Ask AI" button in `frontend/src/styles.css`: show icon-only (hide text label) below 480px viewport width, ensure the button remains visible in the TopNav on mobile (not hidden with `.top-nav__links { display: none }`), and adjust spacing.

**Checkpoint**: "Ask AI" button is visible in the top navigation on all public pages. Clicking it triggers the reCAPTCHA → ChatPanel flow from any page. Button adapts to icon-only on very narrow viewports.

---

## Phase 5: User Story 3 - Preserved Existing Content Below Hero (Priority: P2)

**Goal**: Verify that existing homepage content (About section, CTA section, contact form, social links, CV download) remains fully functional after the hero redesign.

**Independent Test**: Scroll below the hero and verify About and CTA sections render with correct content. Test contact form drawer, CV download, and social links.

### Implementation for User Story 3

- [x] T011 [US3] Verify and fix any layout issues in `frontend/src/pages/HomePage.tsx` where AboutSection and CTASection render below the redesigned hero. Ensure contactOpen state and drawer functionality still works correctly with the ChatContext refactor (contact state remains local to HomePage since it's homepage-only).
- [x] T012 [US3] Verify and fix HeroSection props in `frontend/src/components/home/HeroSection.tsx`: ensure cvUrl, socialMediaLinks, and backgroundImageUrl props are still consumed correctly and rendered in the new centered layout positions.

**Checkpoint**: All existing homepage functionality works. About section, CTA section, contact form, CV download, and social links are all functional and visually correct.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Test updates and final validation across all user stories.

- [x] T013 Update existing frontend tests that reference the old hero two-column structure or HomePage chat state in `frontend/tests/` — update selectors and assertions to match the new centered layout and ChatContext pattern.
- [x] T014 Run full frontend test suite (`cd frontend && npm test`) and fix any failing tests.
- [x] T015 Run quickstart.md verification checklist: manually test all items on desktop and mobile viewports.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 2)**: No dependencies — can start immediately. BLOCKS all user stories.
- **User Story 1 (Phase 3)**: Depends on Phase 2 completion (ChatContext must exist).
- **User Story 2 (Phase 4)**: Depends on Phase 2 completion (ChatContext must exist). Can run in parallel with US1.
- **User Story 3 (Phase 5)**: Depends on Phase 3 completion (hero must be redesigned before verifying content below it).
- **Polish (Phase 6)**: Depends on all user stories being complete.

### User Story Dependencies

- **User Story 1 (P1)**: Depends on Foundational only. No dependencies on other stories.
- **User Story 2 (P1)**: Depends on Foundational only. No dependencies on other stories. Can be implemented in parallel with US1.
- **User Story 3 (P2)**: Depends on US1 (hero redesign must be complete to verify content below it).

### Within Each User Story

- CSS changes can be done alongside component changes (same developer)
- Mobile responsive styles depend on desktop styles being in place

### Parallel Opportunities

- T004 and T008 can run in parallel (different files: HeroSection.tsx vs TopNav.tsx)
- T005/T006 and T009/T010 can run in parallel (different CSS class namespaces in styles.css)
- US1 (Phase 3) and US2 (Phase 4) can run in parallel after Phase 2 completes

---

## Parallel Example: After Foundational Phase

```bash
# Launch US1 and US2 in parallel (different components):
Task: "T004 [US1] Restructure HeroSection.tsx to centered layout"
Task: "T008 [US2] Add Ask AI button to TopNav.tsx"

# Then in parallel:
Task: "T005 [US1] Update hero CSS in styles.css"
Task: "T009 [US2] Add Ask AI button CSS in styles.css"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 2: Foundational (ChatContext + layout-level rendering)
2. Complete Phase 3: User Story 1 (centered hero)
3. **STOP and VALIDATE**: Test hero independently — chat flow works, centered layout renders
4. Deploy/demo if ready

### Incremental Delivery

1. Complete Foundational → ChatContext ready
2. Add User Story 1 → Test independently → Centered hero with AI chat (MVP!)
3. Add User Story 2 → Test independently → Ask AI from any page
4. Add User Story 3 → Verify existing content preserved
5. Polish → Test updates, full validation

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- No new backend changes or API contracts required
- All CSS changes go in the single `frontend/src/styles.css` file (per constitution)
- All icons must use Lucide React (per constitution)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
