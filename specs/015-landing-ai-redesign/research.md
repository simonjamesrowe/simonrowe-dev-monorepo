# Research: Landing Page AI Redesign

**Date**: 2026-04-06
**Branch**: `015-landing-ai-redesign`

## Research Findings

### 1. Chat State Lifting Strategy

**Decision**: Create a `ChatContext` React context provider to share chat state (chatOpen, recaptchaVerified, chatQuery, showRecaptcha) across all public pages.

**Rationale**: The current chat state lives entirely in `HomePage.tsx` via `useState` hooks. Since the "Ask AI" button in TopNav needs to trigger the same RecaptchaGate → ChatPanel flow from any page, the state must be lifted to a level above the page components. A React context is the simplest approach that avoids prop drilling through the layout hierarchy.

**Alternatives considered**:
- **Prop drilling through PublicLayout**: Rejected — PublicLayout is an inline function in App.tsx using `<Outlet />`, so child pages can't receive props directly without wrapping in context anyway.
- **Global state library (Zustand/Redux)**: Rejected — overkill for 4 boolean/string states. Violates Principle V (simplicity).
- **URL-based state (query params)**: Rejected — reCAPTCHA verification state shouldn't persist in URL; would cause re-verification on navigation.

### 2. Hero Layout Transformation

**Decision**: Replace the CSS grid `grid-template-columns: 7fr 5fr` with a single-column centered flex layout. The chat input becomes the primary CTA, positioned directly below the headline and subtitle.

**Rationale**: The reference image shows a centered, vertically stacked layout with headline → subtitle → chat input → suggested prompts. This is a CSS-only change to the hero grid structure, with minor HTML restructuring to place the chat input inline rather than in a separate right column.

**Alternatives considered**:
- **Keep grid but center both columns**: Rejected — still creates visual separation between headline and chat input, which contradicts the "AI-prominent" design goal.
- **Full-width hero with overlapping elements**: Rejected — more complex CSS with absolute positioning; harder to maintain responsiveness.

### 3. "Ask AI" Button Placement and Styling

**Decision**: Add a pill-shaped "ASK AI" button with a `MessageSquare` Lucide icon to TopNav, positioned before the SiteSearch component in the actions area.

**Rationale**: The reference image shows the button as an outlined pill with icon + text label, visually distinct from the search bar. Placing it before search ensures it's the first action element visitors see. On mobile (< 768px), the button should remain visible (not hidden with nav links) but can be icon-only to save space.

**Alternatives considered**:
- **Floating action button (FAB)**: Rejected — FABs are mobile patterns that feel out of place on desktop; also conflicts with existing tour button placement.
- **Inside search bar as prefix**: Rejected — conflates two distinct actions (search vs AI chat).
- **After search bar**: Rejected — reference image clearly shows it before search.

### 4. Social Links and CV Download Placement

**Decision**: Move social links and CV download button below the suggested prompt chips in the centered hero layout.

**Rationale**: In the new centered layout, the vertical flow is: headline → subtitle → chat input → suggested prompts → social/CV links. This maintains all existing functionality while keeping the AI chat as the primary focus above the social links.

**Alternatives considered**:
- **Social links in a horizontal bar above the headline**: Rejected — steals visual attention from the headline.
- **Social links moved to footer only**: Rejected — reduces discoverability; spec requires they remain in the hero section.
- **Social links flanking the headline**: Rejected — breaks the single-column centered aesthetic.

### 5. Mobile Responsiveness Strategy

**Decision**: The centered single-column hero is inherently mobile-friendly. The "Ask AI" button in TopNav will show icon-only below 480px and full pill (icon + text) above.

**Rationale**: The current hero already stacks to single-column at 768px. The new design starts as single-column, so mobile just needs width constraints (full-width chat input, wrapping prompt chips). The TopNav "Ask AI" button needs a breakpoint for text label visibility.

**Alternatives considered**:
- **Hide "Ask AI" on mobile entirely**: Rejected — spec FR-012 explicitly requires it to remain visible.
- **Move "Ask AI" to bottom tab bar on mobile**: Rejected — no bottom tab bar exists; adding one is scope creep.

### 6. ChatPanel and RecaptchaGate Rendering Location

**Decision**: Move ChatPanel and RecaptchaGate rendering from HomePage into PublicLayout (in App.tsx), consuming state from ChatContext.

**Rationale**: Currently these are rendered in HomePage.tsx. For them to appear on any page when triggered via the TopNav "Ask AI" button, they must render at the layout level. The PublicLayout function in App.tsx already wraps all public routes and is the natural location.

**Alternatives considered**:
- **Render in TopNav itself**: Rejected — TopNav is a navigation component; rendering modals from it violates separation of concerns.
- **Portal-based rendering from HomePage**: Rejected — ChatPanel wouldn't render at all on non-homepage pages since HomePage component isn't mounted.
