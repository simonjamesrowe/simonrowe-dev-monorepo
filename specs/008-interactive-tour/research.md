# Research: Interactive Tour

**Feature**: 008-interactive-tour
**Date**: 2026-02-21
**Phase**: 0 (Research)

## 1. Tour Library Evaluation

### 1.1 react-joyride

**What it is**: The most popular React tour library (4k+ GitHub stars). Provides declarative step-based tours with spotlight highlighting, tooltips, and navigation controls.

**Pros**:
- Mature ecosystem with active maintenance
- Built-in spotlight overlay using a combination of SVG masks and CSS
- Supports custom tooltip rendering via `tooltipComponent` prop
- Handles scroll-into-view automatically
- Callback system for step change events (useful for search simulation trigger)

**Cons**:
- Heavy dependency tree (~45KB gzipped) including react-floater and popper.js
- Spotlight implementation uses SVG overlay which can interfere with pointer events on complex layouts
- Search simulation requires hooking into the `callback` prop with step index matching -- not natively supported
- Customizing the overlay appearance (e.g., border color on spotlight) requires CSS overrides on internal class names that may change between versions
- Does not support markdown rendering in step content without custom tooltip component

**Assessment**: Viable but adds significant bundle weight for functionality that can be achieved with less code. The custom tooltip requirement (markdown, title images) means the built-in UI would be largely replaced anyway.

### 1.2 shepherd.js (with react-shepherd wrapper)

**What it is**: A framework-agnostic tour library with a React wrapper. Uses Popper.js for tooltip positioning and SVG for overlay.

**Pros**:
- Framework-agnostic core with thin React wrapper
- Imperative API gives fine-grained control over step transitions
- Supports custom button actions per step
- Modal overlay with element highlighting

**Cons**:
- React wrapper (`react-shepherd`) is less actively maintained than the core library
- Requires manual integration with React state management (no built-in context provider)
- Bundle size comparable to react-joyride (~40KB gzipped)
- SVG overlay approach has same pointer-event limitations as react-joyride
- Imperative API conflicts with React's declarative paradigm -- requires refs and effect hooks for lifecycle management

**Assessment**: The imperative API adds integration complexity without clear benefit over react-joyride. The less active React wrapper is a maintenance risk.

### 1.3 intro.js (with intro.js-react)

**What it is**: The library used in the legacy simonrowe.dev React UI. Provides step-based introductions with element highlighting.

**Pros**:
- Known quantity -- the existing implementation uses this library successfully
- Lightweight compared to react-joyride (~15KB gzipped)
- Simple API for step definition and navigation

**Cons**:
- The legacy implementation uses jQuery for DOM manipulation (`$(".tour-search > div > div > input")`) alongside React, which is an anti-pattern
- The React wrapper (`intro.js-react`) has limited maintenance activity
- Customization of the overlay appearance requires CSS class overrides on `.introjs-*` selectors
- Does not natively support React elements as step content -- the legacy code embeds HTML strings for images
- The legacy implementation uses Redux for simulation state, which the new architecture replaces with React context
- License changed to AGPL in recent versions, which may pose compliance concerns

**Assessment**: While proven in the existing codebase, the jQuery dependency pattern and HTML string injection for content are anti-patterns that should not be carried forward. The AGPL license change is also a concern.

### 1.4 Custom Implementation (Selected)

**What it is**: A purpose-built tour system using standard React patterns (context, hooks, portals) and CSS for the overlay/spotlight effect.

**Pros**:
- Zero additional runtime dependencies beyond what the project already uses (react-markdown)
- Full control over overlay behavior, spotlight rendering, tooltip positioning, and search simulation timing
- Clean React patterns: context provider for state, custom hook for consumption, portals for overlay rendering
- CSS clip-path or box-shadow approach for spotlight eliminates SVG overlay pointer-event issues
- Markdown rendering uses the existing react-markdown dependency
- Search simulation integrates directly into the tour state machine without Redux or jQuery
- Bundle size: effectively 0KB additional dependencies

**Cons**:
- Requires implementing tooltip positioning logic (element bounding rect + viewport boundary detection)
- Requires implementing scroll-into-view behavior for off-screen target elements
- More initial development effort than installing a library

**Assessment**: Selected. The development cost is modest (6 components, 1 hook, 1 service) and eliminates all third-party tour library dependencies. The existing react-markdown dependency handles description formatting. The custom approach avoids the anti-patterns found in the legacy implementation (jQuery DOM manipulation, HTML string injection, Redux for UI-only state).

## 2. Overlay and Spotlight CSS Techniques

### 2.1 CSS box-shadow Approach (Selected)

**Technique**: Apply a large `box-shadow` with `spread-radius` to the highlighted element, creating the appearance of a full-page overlay with a spotlight cutout.

```css
.tour-spotlight {
  position: relative;
  z-index: 9999;
  box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.6);
  border-radius: 4px;
  pointer-events: none;
}
```

**Pros**:
- Single CSS property creates the entire overlay effect
- No need for a separate full-screen overlay element
- The highlighted element remains naturally in the DOM flow
- Transitions between steps can be animated with CSS `transition: box-shadow 0.3s`
- Works in all modern browsers (Chrome, Firefox, Safari, Edge)
- No pointer-event issues -- the shadow is non-interactive by default

**Cons**:
- The "overlay" is technically a shadow on a positioned element, not a true overlay
- Requires the spotlight element to have an applied class or inline style
- Cannot easily add click-to-dismiss on the overlay area (shadow does not receive click events)

**Mitigation for click-to-dismiss**: Use a transparent full-screen `div` behind the spotlight element (z-index: 9998) that captures clicks for "exit on overlay click" behavior.

### 2.2 CSS clip-path Approach (Alternative)

**Technique**: A full-screen overlay `div` with `clip-path: polygon()` that cuts out the highlighted element area.

```css
.tour-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  z-index: 9998;
  clip-path: polygon(
    0% 0%, 0% 100%, /* left edge */
    LEFT 100%, LEFT TOP, /* bottom-left of cutout */
    RIGHT TOP, RIGHT BOTTOM, /* right edge of cutout */
    LEFT BOTTOM, LEFT 100%, /* bottom edge back */
    100% 100%, 100% 0% /* right edge of overlay */
  );
}
```

**Pros**:
- True overlay element that receives click events (enables click-to-dismiss)
- Clean separation between overlay and highlighted element

**Cons**:
- Requires JavaScript to compute polygon coordinates on every step change and window resize
- Animation between steps requires animating the polygon points, which is less smooth than box-shadow transitions
- More complex implementation for the same visual result

### 2.3 Decision

The **box-shadow approach** is selected for its simplicity. A transparent click-capture `div` at z-index 9998 handles the overlay click-to-dismiss requirement. The highlighted element receives the box-shadow class via a React ref resolved from the `targetSelector` CSS selector. Transition animations use CSS `transition` on the box-shadow property for smooth step changes.

## 3. Search Simulation Timing Approach

### 3.1 Legacy Pattern Analysis

The existing implementation in `SimulateService.tsx` uses:
1. A Redux thunk dispatches a `SEARCH` action with the query string
2. jQuery selects the search input element: `$(".tour-search > div > div > input")`
3. A `for` loop types characters with 50ms delays using `await sleep(50)`
4. Three queries are typed sequentially with 1500ms pauses between them

**Problems with this approach**:
- jQuery DOM manipulation bypasses React's rendering cycle
- Redux state management for ephemeral UI-only state is over-engineered
- The `sleep()` promises are not cancellable -- if the user exits the tour during simulation, the typing continues
- No cleanup mechanism for the search input value when simulation ends

### 3.2 New Approach (Selected)

**AbortController + requestAnimationFrame pattern**:

```typescript
// Simplified concept
const simulateSearch = async (queries: string[], signal: AbortSignal) => {
  for (const query of queries) {
    for (let i = 0; i <= query.length; i++) {
      if (signal.aborted) return;
      setSearchValue(query.substring(0, i));
      await delay(50, signal);
    }
    if (signal.aborted) return;
    await delay(1500, signal);
  }
};

const delay = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => {
      clearTimeout(timer);
      resolve(); // resolve rather than reject for clean exit
    });
  });
```

**Key design decisions**:
- **AbortController** for cancellation: When the user clicks "Next" or "Exit", the controller's `abort()` is called, immediately stopping all pending timeouts. This addresses the legacy bug where simulation continued after tour exit.
- **React-controlled input value**: Instead of jQuery DOM manipulation, the simulation updates a React state value that is passed to the search component as a controlled prop. This keeps React in control of the DOM.
- **No Redux**: Simulation state (current query text, running flag) lives in the TourProvider context. It is local to the tour lifecycle and does not need global state management.
- **Configurable queries**: The three search queries (`"spring boot"`, `"spring boot kubernetes"`, `"spring boot kubernetes jenkins"`) are defined as constants in the SearchSimulation component, matching the existing behavior.

### 3.3 Timing Budget

Per SC-004, the simulation must demonstrate at least 3 progressively longer queries within 10 seconds.

| Query | Characters | Typing Time (50ms/char) | Pause After | Cumulative |
|-------|-----------|------------------------|-------------|------------|
| `spring boot` | 11 | 550ms | 1500ms | 2050ms |
| `spring boot kubernetes` | 22 | 1100ms | 1500ms | 4650ms |
| `spring boot kubernetes jenkins` | 30 | 1500ms | 0ms | 6150ms |

**Total**: ~6.2 seconds, well within the 10-second budget. The character typing speed of 50ms per character matches the legacy implementation and produces a natural-looking typing effect.

## 4. Responsive Tour Hiding

### 4.1 Approach

The "Take a Tour" button must be hidden on viewports below 768px (SC-003). Two complementary mechanisms:

**CSS Media Query (primary)**:
```css
@media (max-width: 767px) {
  .tour-button {
    display: none;
  }
}
```

This ensures the button is never visible on mobile regardless of JavaScript state.

**JavaScript matchMedia listener (runtime)**:
```typescript
const isDesktop = window.matchMedia('(min-width: 768px)');
```

This handles the edge case where a user resizes their browser from desktop to mobile while the tour is active. The TourProvider listens for media query changes and automatically exits the tour if the viewport drops below 768px during an active session.

### 4.2 Active Tour + Resize Behavior

When a user resizes from desktop to mobile during an active tour:
1. The `matchMedia` change event fires in TourProvider
2. TourProvider calls the exit handler, which:
   - Aborts any active search simulation via AbortController
   - Removes the spotlight class from the current target element
   - Resets tour state (currentStep to -1, isActive to false)
   - Clears the search input value if simulation was running
3. The CSS media query simultaneously hides the "Take a Tour" button
4. All overlay elements are removed from the DOM via conditional rendering

## 5. Tooltip Positioning Strategy

### 5.1 Position Calculation

Each tour step has a configured `position` field (`top`, `bottom`, `left`, `right`, `center`). The tooltip is positioned relative to the target element's bounding rectangle.

**Algorithm**:
1. Resolve the target element using `document.querySelector(targetSelector)`
2. Get the element's bounding rect via `getBoundingClientRect()`
3. Scroll the element into view if not visible: `element.scrollIntoView({ behavior: 'smooth', block: 'center' })`
4. Calculate tooltip position based on the configured position and element rect
5. Apply viewport boundary clamping to prevent the tooltip from overflowing the screen

**Position offsets** (with 12px gap):
- `top`: tooltip bottom edge aligns with element top edge minus gap
- `bottom`: tooltip top edge aligns with element bottom edge plus gap
- `left`: tooltip right edge aligns with element left edge minus gap
- `right`: tooltip left edge aligns with element right edge plus gap
- `center`: tooltip centered over the element (for page-wide elements like banners)

### 5.2 Viewport Boundary Clamping

After computing the initial position, clamp the tooltip coordinates so that:
- `left >= 16px` (minimum padding from viewport edge)
- `right <= viewport width - 16px`
- `top >= 16px`
- `bottom <= viewport height - 16px`

If clamping would overlap the highlighted element, fall back to the opposite position (e.g., `top` falls back to `bottom`).

## 6. Key Technical Decisions Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Tour library | Custom implementation | Zero new dependencies; full control over overlay, tooltip, and simulation; avoids legacy anti-patterns (jQuery, HTML strings, Redux for UI state) |
| Overlay technique | CSS box-shadow on spotlight element | Simplest approach; single CSS property; smooth transitions; no pointer-event issues |
| Search simulation cancellation | AbortController | Native browser API; immediate cleanup on tour exit; no dangling timeouts |
| Search input control | React controlled component via context | Replaces jQuery DOM manipulation; keeps React in control of rendering |
| Tour state management | React Context + useReducer | Local to tour lifecycle; no global Redux store needed; clean provider/consumer pattern |
| Markdown rendering | react-markdown (existing dep) | Already in the project; supports bold, italic, lists, paragraphs per FR-008 |
| Responsive hiding | CSS media query + JS matchMedia | CSS for button visibility; JS for active tour cleanup on resize |
| Tooltip positioning | Manual calculation from getBoundingClientRect | Avoids Popper.js dependency; positions are configured per step (not dynamic); viewport clamping handles edge cases |
