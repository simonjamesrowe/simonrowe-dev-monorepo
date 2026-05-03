# Mobile Landing Page Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tighten the mobile (`max-width: 768px`) landing page on `/` — fix top-bar control alignment, simplify the hero to chat-only, move CV + socials into a new "Connect" strip, and add a "Read more" toggle to the About bio.

**Architecture:** CSS-driven where possible. New `ConnectStrip` React component sits between `<AboutSection>` and `<CTASection>` and is hidden on desktop via media query. A reusable `useMediaQuery` hook drives the bio collapse/expand state. No backend changes.

**Tech Stack:** React 18 + TypeScript + Vite, plain CSS (BEM, single `frontend/src/styles.css`), Vitest + @testing-library/react, lucide-react icons.

**Spec:** `docs/superpowers/specs/2026-05-02-mobile-landing-page-cleanup-design.md`

---

## File Map

- **Create**
  - `frontend/src/hooks/useMediaQuery.ts` — reactive `matchMedia` hook
  - `frontend/tests/hooks/useMediaQuery.test.ts` — hook tests
  - `frontend/src/components/home/ConnectStrip.tsx` — mobile-only CV + socials block
  - `frontend/tests/components/home/ConnectStrip.test.tsx` — component tests
  - `frontend/tests/components/home/AboutSection.test.tsx` — bio truncation tests
- **Modify**
  - `frontend/src/styles.css` — top-nav mobile sizing, hero element hiding, ConnectStrip styles, about-section "Read more" styles
  - `frontend/src/components/home/AboutSection.tsx` — collapsed/expanded paragraphs + toggle button
  - `frontend/src/pages/HomePage.tsx` — render `<ConnectStrip>` between bio and CTA

---

### Task 1: `useMediaQuery` hook

A reactive hook returning `true` when the supplied CSS media query matches. Used by `AboutSection` to decide whether to truncate the bio.

**Files:**
- Create: `frontend/src/hooks/useMediaQuery.ts`
- Test: `frontend/tests/hooks/useMediaQuery.test.ts`

- [ ] **Step 1: Write the failing tests**

Create `frontend/tests/hooks/useMediaQuery.test.ts`:

```typescript
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useMediaQuery } from '../../src/hooks/useMediaQuery'

type Listener = (event: MediaQueryListEvent) => void

function mockMatchMedia(matches: boolean) {
  const listeners = new Set<Listener>()
  const mql = {
    matches,
    media: '',
    onchange: null,
    addEventListener: (_: string, fn: Listener) => listeners.add(fn),
    removeEventListener: (_: string, fn: Listener) => listeners.delete(fn),
    addListener: (fn: Listener) => listeners.add(fn),
    removeListener: (fn: Listener) => listeners.delete(fn),
    dispatchEvent: () => true,
  } as unknown as MediaQueryList
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => {
      ;(mql as unknown as { media: string }).media = query
      return mql
    }),
  )
  return {
    mql,
    fire(next: boolean) {
      ;(mql as unknown as { matches: boolean }).matches = next
      listeners.forEach(fn => fn({ matches: next } as MediaQueryListEvent))
    },
  }
}

describe('useMediaQuery', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns initial match value', () => {
    mockMatchMedia(true)
    const { result } = renderHook(() => useMediaQuery('(max-width: 768px)'))
    expect(result.current).toBe(true)
  })

  it('returns false when media does not match', () => {
    mockMatchMedia(false)
    const { result } = renderHook(() => useMediaQuery('(max-width: 768px)'))
    expect(result.current).toBe(false)
  })

  it('updates when the media query changes', () => {
    const { fire } = mockMatchMedia(false)
    const { result } = renderHook(() => useMediaQuery('(max-width: 768px)'))
    expect(result.current).toBe(false)
    act(() => fire(true))
    expect(result.current).toBe(true)
  })

  it('removes its listener on unmount', () => {
    const { mql } = mockMatchMedia(true)
    const removeSpy = vi.spyOn(mql, 'removeEventListener')
    const { unmount } = renderHook(() => useMediaQuery('(max-width: 768px)'))
    unmount()
    expect(removeSpy).toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `cd frontend && npm test -- useMediaQuery`
Expected: FAIL with "Cannot find module '../../src/hooks/useMediaQuery'".

- [ ] **Step 3: Implement the hook**

Create `frontend/src/hooks/useMediaQuery.ts`:

```typescript
import { useEffect, useState } from 'react'

export function useMediaQuery(query: string): boolean {
  const getMatch = () =>
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia(query).matches
      : false

  const [matches, setMatches] = useState<boolean>(getMatch)

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return
    }
    const mql = window.matchMedia(query)
    const handler = (event: MediaQueryListEvent) => setMatches(event.matches)
    setMatches(mql.matches)
    mql.addEventListener('change', handler)
    return () => mql.removeEventListener('change', handler)
  }, [query])

  return matches
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `cd frontend && npm test -- useMediaQuery`
Expected: PASS, all 4 cases green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/hooks/useMediaQuery.ts frontend/tests/hooks/useMediaQuery.test.ts
git commit -m "feat: add useMediaQuery hook"
```

---

### Task 2: Top-bar alignment fix (44×44 controls)

Fix the search input and mobile-menu hamburger so they share a vertical center inside the 56px nav. CSS only — both controls become 44×44.

**Files:**
- Modify: `frontend/src/styles.css` (mobile media query block ~line 535, plus mobile-menu trigger rule ~line 638)

- [ ] **Step 1: Update the search input sizing inside the mobile media query**

Open `frontend/src/styles.css` and locate the existing `@media (max-width: 768px)` block that contains `.site-search__input` (around line 558–567). Replace just the `.site-search__input` rule with a fixed 44px height:

```css
  .site-search {
    min-width: 0;
    flex: 1;
    margin-right: 3rem;
  }

  .site-search__input {
    box-sizing: border-box;
    height: 44px;
    padding: 0 2.75rem 0 0.75rem;
    font-size: 16px;
    line-height: 44px;
  }
```

(Keeps existing margin/min-width and the trailing `.site-search__suggestions { display: none }` rule below — only the `.site-search__input` rule body changes.)

- [ ] **Step 2: Resize the mobile-menu trigger to 44×44**

Locate `.mobile-menu__trigger` (around line 638) and update it so the hit target is exactly 44×44 and vertically centred against a 56px nav:

```css
.mobile-menu__trigger {
  position: fixed;
  top: 6px;            /* (56 - 44) / 2 */
  right: 1rem;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--on-surface);
  cursor: pointer;
}
```

(Leave the `:hover` rule below it unchanged.)

- [ ] **Step 3: Verify visually with Playwright**

Run the frontend dev server (`./scripts/start-frontend.sh`) and use Playwright at 390×844:

```javascript
// inside browser_evaluate
() => {
  const search = document.querySelector('.site-search__input')?.getBoundingClientRect()
  const ham = document.querySelector('.mobile-menu__trigger')?.getBoundingClientRect()
  return {
    search: search && { y: search.y, h: search.height, mid: search.y + search.height / 2 },
    ham: ham && { y: ham.y, h: ham.height, mid: ham.y + ham.height / 2 },
  }
}
```

Expected: both `mid` values within 1px of each other; both `h` values equal to 44.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/styles.css
git commit -m "fix: align mobile top-bar search and hamburger to 44px"
```

---

### Task 3: Hero simplification CSS

Hide the chat-intro paragraph, suggested-prompt chips, and the actions row (CV button + socials) on mobile only. Clamp the tagline to one line.

**Files:**
- Modify: `frontend/src/styles.css` (hero responsive block ~line 982)

- [ ] **Step 1: Extend the hero mobile media query**

Locate the `@media (max-width: 768px)` block that contains `.hero` (around line 982). Append the following rules inside that same block, after the existing `.hero__chat-input { max-width: 100% }` rule:

```css
  .hero__chat-intro,
  .hero__prompts,
  .hero__actions {
    display: none;
  }

  .hero__tagline {
    display: -webkit-box;
    -webkit-line-clamp: 1;
    -webkit-box-orient: vertical;
    overflow: hidden;
    text-overflow: ellipsis;
  }
```

- [ ] **Step 2: Verify visually with Playwright**

With the dev server running, navigate to `http://localhost:5173/` at 390×844 and run:

```javascript
() => {
  const probe = sel => {
    const el = document.querySelector(sel)
    if (!el) return null
    const cs = getComputedStyle(el)
    return { display: cs.display, h: el.getBoundingClientRect().height }
  }
  return {
    intro: probe('.hero__chat-intro'),
    prompts: probe('.hero__prompts'),
    actions: probe('.hero__actions'),
    tagline: probe('.hero__tagline'),
    hero: probe('.hero'),
  }
}
```

Expected: `intro`, `prompts`, `actions` each have `display: "none"`. `hero` height noticeably less than the original 679px (target < 480px). `tagline` height roughly one line (~20–24px).

Then resize the browser to 1280×800 and re-run — `intro`, `prompts`, `actions` should NOT be `display: none`.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/styles.css
git commit -m "feat: simplify mobile hero to name, tagline, chat input only"
```

---

### Task 4: `ConnectStrip` component

A new mobile-only block holding the Download CV button and the social-icon row. Lives between `<AboutSection>` and `<CTASection>`.

**Files:**
- Create: `frontend/src/components/home/ConnectStrip.tsx`
- Test: `frontend/tests/components/home/ConnectStrip.test.tsx`
- Modify: `frontend/src/styles.css` (append new section)

- [ ] **Step 1: Write the failing tests**

Create `frontend/tests/components/home/ConnectStrip.test.tsx`:

```typescript
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ConnectStrip } from '../../../src/components/home/ConnectStrip'
import type { SocialMediaLink } from '../../../src/types/SocialMediaLink'

const links: SocialMediaLink[] = [
  { type: 'github', name: 'GitHub', url: 'https://github.com/simon' },
  { type: 'linkedin', name: 'LinkedIn', url: 'https://www.linkedin.com/in/simon' },
  { type: 'twitter', name: 'Twitter', url: 'https://twitter.com/simon' },
]

describe('ConnectStrip', () => {
  it('renders the Connect heading', () => {
    render(<ConnectStrip socialMediaLinks={links} />)
    expect(screen.getByText(/connect/i)).toBeInTheDocument()
  })

  it('renders a Download CV link pointing at the resume endpoint', () => {
    render(<ConnectStrip socialMediaLinks={links} />)
    const cv = screen.getByRole('link', { name: /download cv/i })
    expect(cv.getAttribute('href')).toMatch(/\/api\/resume$/)
    expect(cv).toHaveAttribute('target', '_blank')
  })

  it('renders a link for each social media entry, deduplicating by type', () => {
    const dup = [...links, { type: 'github', name: 'GitHub Alt', url: 'https://github.com/other' }]
    render(<ConnectStrip socialMediaLinks={dup} />)
    expect(screen.getAllByRole('link', { name: /github/i })).toHaveLength(1)
    expect(screen.getAllByRole('link', { name: /linkedin/i })).toHaveLength(1)
    expect(screen.getAllByRole('link', { name: /twitter/i })).toHaveLength(1)
  })

  it('renders nothing visible when no links and no CV are available', () => {
    const { container } = render(<ConnectStrip socialMediaLinks={[]} />)
    // Heading + CV button still render — only the social row should be empty
    expect(container.querySelector('.connect-strip__socials')).toBeNull()
  })
})
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `cd frontend && npm test -- ConnectStrip`
Expected: FAIL with module-not-found.

- [ ] **Step 3: Implement the component**

Create `frontend/src/components/home/ConnectStrip.tsx`:

```tsx
import { Download, Github, Linkedin, Twitter } from 'lucide-react'

import { API_BASE_URL } from '../../config/api'
import type { SocialMediaLink } from '../../types/SocialMediaLink'

interface ConnectStripProps {
  socialMediaLinks?: SocialMediaLink[]
}

const socialIcons: Record<string, React.ReactNode> = {
  github: <Github size={20} />,
  linkedin: <Linkedin size={20} />,
  twitter: <Twitter size={20} />,
}

const socialLabels: Record<string, string> = {
  github: 'GitHub',
  linkedin: 'LinkedIn',
  twitter: 'Twitter',
}

export function ConnectStrip({ socialMediaLinks = [] }: ConnectStripProps) {
  const uniqueLinks = socialMediaLinks.filter(
    (link, index, arr) => arr.findIndex(l => l.type === link.type) === index,
  )

  return (
    <section className="connect-strip" aria-label="Connect">
      <p className="connect-strip__eyebrow">Connect</p>
      <a
        className="button button--primary connect-strip__cv"
        href={`${API_BASE_URL}/api/resume`}
        rel="noopener noreferrer"
        target="_blank"
      >
        <Download size={16} /> Download CV
      </a>
      {uniqueLinks.length > 0 && (
        <ul className="connect-strip__socials">
          {uniqueLinks.map(link => (
            <li key={link.url}>
              <a
                aria-label={socialLabels[link.type] ?? link.name}
                className="connect-strip__social-link"
                href={link.url}
                rel="noopener noreferrer"
                target="_blank"
              >
                {socialIcons[link.type] ?? link.type}
              </a>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `cd frontend && npm test -- ConnectStrip`
Expected: PASS, all 4 cases green.

- [ ] **Step 5: Add component styles**

Append to the end of `frontend/src/styles.css`:

```css
/* ==================== Connect Strip (mobile only) ==================== */

.connect-strip {
  display: none;
}

@media (max-width: 768px) {
  .connect-strip {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 1rem;
    padding: 1.5rem 1.25rem;
    margin: 0 1rem 1.5rem;
    background: var(--surface-container-low);
    border: 1px solid var(--border-faint);
    border-radius: 1rem;
  }

  .connect-strip__eyebrow {
    margin: 0;
    font-size: 0.75rem;
    text-transform: uppercase;
    letter-spacing: 0.12em;
    color: var(--on-surface-variant);
  }

  .connect-strip__cv {
    width: 100%;
    justify-content: center;
  }

  .connect-strip__socials {
    display: flex;
    gap: 1.25rem;
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .connect-strip__social-link {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    border-radius: 50%;
    color: var(--on-surface-variant);
    transition: background var(--transition-fast), color var(--transition-fast);
  }

  .connect-strip__social-link:hover {
    background: var(--surface-container-high);
    color: var(--on-surface);
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/home/ConnectStrip.tsx frontend/tests/components/home/ConnectStrip.test.tsx frontend/src/styles.css
git commit -m "feat: add mobile-only ConnectStrip with CV button and socials"
```

---

### Task 5: Wire `ConnectStrip` into `HomePage`

Render the new component between `<AboutSection>` and `<CTASection>`.

**Files:**
- Modify: `frontend/src/pages/HomePage.tsx`

- [ ] **Step 1: Update HomePage**

Open `frontend/src/pages/HomePage.tsx`. Add the import alongside the other home component imports:

```typescript
import { ConnectStrip } from '../components/home/ConnectStrip'
```

Then inside the returned JSX, insert `<ConnectStrip>` between `<AboutSection>` and `<CTASection>`:

```tsx
return (
  <>
    <HeroSection
      name={profile.name}
      title={profile.title}
      tagline={profile.headline}
      backgroundImageUrl={profile.backgroundImage?.url}
      socialMediaLinks={profile.socialMediaLinks}
    />
    <AboutSection profile={profile} onContact={openContact} />
    <ConnectStrip socialMediaLinks={profile.socialMediaLinks} />
    <CTASection onContact={openContact} />
    <ContactDrawer open={contactOpen} onClose={closeContact} />
  </>
)
```

- [ ] **Step 2: Run the existing HomePage test to confirm no regression**

Run: `cd frontend && npm test -- HomePage`
Expected: PASS (existing tests untouched, new component renders harmlessly).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/HomePage.tsx
git commit -m "feat: render ConnectStrip on HomePage between bio and CTA"
```

---

### Task 6: About bio "Read more" toggle

On mobile only, show the first two paragraphs of `profile.description` with a "Read more" / "Read less" toggle.

**Files:**
- Modify: `frontend/src/components/home/AboutSection.tsx`
- Create: `frontend/tests/components/home/AboutSection.test.tsx`
- Modify: `frontend/src/styles.css` (append toggle button styles)

- [ ] **Step 1: Write the failing tests**

Create `frontend/tests/components/home/AboutSection.test.tsx`:

```typescript
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AboutSection } from '../../../src/components/home/AboutSection'
import type { Profile } from '../../../src/types/Profile'

const description = [
  'Para one. Driven by business value.',
  'Para two. Years of experience leading teams.',
  'Para three. Strong advocate for AI-native engineering.',
  'Para four. Toolkit includes Java, Kotlin, Spring.',
].join('\n\n')

const profile: Profile = {
  name: 'Simon Rowe',
  firstName: 'Simon',
  lastName: 'Rowe',
  title: 'Engineer',
  headline: 'Headline',
  description,
  profileImage: { url: '/img.png' },
  sidebarImage: { url: '/img.png' },
  backgroundImage: { url: '/img.png' },
  mobileBackgroundImage: { url: '/img.png' },
  location: '',
  phoneNumber: '',
  primaryEmail: '',
  socialMediaLinks: [],
}

function setMatchMedia(matches: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  )
}

describe('AboutSection', () => {
  beforeEach(() => vi.unstubAllGlobals())
  afterEach(() => vi.unstubAllGlobals())

  it('shows full description on desktop and renders no Read more button', () => {
    setMatchMedia(false)
    render(<AboutSection profile={profile} onContact={() => {}} />)
    expect(screen.getByText(/Para one\./)).toBeInTheDocument()
    expect(screen.getByText(/Para four\./)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /read more/i })).not.toBeInTheDocument()
  })

  it('shows only the first two paragraphs on mobile by default', () => {
    setMatchMedia(true)
    render(<AboutSection profile={profile} onContact={() => {}} />)
    expect(screen.getByText(/Para one\./)).toBeInTheDocument()
    expect(screen.getByText(/Para two\./)).toBeInTheDocument()
    expect(screen.queryByText(/Para three\./)).not.toBeInTheDocument()
    expect(screen.queryByText(/Para four\./)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /read more/i })).toBeInTheDocument()
  })

  it('expands all paragraphs when Read more is clicked, then collapses again', () => {
    setMatchMedia(true)
    render(<AboutSection profile={profile} onContact={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /read more/i }))
    expect(screen.getByText(/Para three\./)).toBeInTheDocument()
    expect(screen.getByText(/Para four\./)).toBeInTheDocument()
    const lessButton = screen.getByRole('button', { name: /read less/i })
    fireEvent.click(lessButton)
    expect(screen.queryByText(/Para three\./)).not.toBeInTheDocument()
  })

  it('does not render a Read more button if the description has fewer than 3 paragraphs', () => {
    setMatchMedia(true)
    const shortProfile = { ...profile, description: 'Only one para.\n\nAnd a second.' }
    render(<AboutSection profile={shortProfile} onContact={() => {}} />)
    expect(screen.queryByRole('button', { name: /read more/i })).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `cd frontend && npm test -- AboutSection`
Expected: FAIL — current `AboutSection` always renders the full description and has no toggle.

- [ ] **Step 3: Update `AboutSection.tsx`**

Replace the contents of `frontend/src/components/home/AboutSection.tsx` with:

```tsx
import { useMemo, useState } from 'react'
import ReactMarkdown from 'react-markdown'

import { API_BASE_URL } from '../../config/api'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import type { Profile } from '../../types/Profile'

interface AboutSectionProps {
  profile: Profile
  onContact: () => void
}

const PREVIEW_PARAGRAPHS = 2

export function AboutSection({ profile, onContact }: AboutSectionProps) {
  const isMobile = useMediaQuery('(max-width: 768px)')
  const [expanded, setExpanded] = useState(false)

  const paragraphs = useMemo(
    () => profile.description.split(/\n\s*\n/).filter(p => p.trim().length > 0),
    [profile.description],
  )

  const isTruncatable = isMobile && paragraphs.length > PREVIEW_PARAGRAPHS
  const visibleMarkdown = isTruncatable && !expanded
    ? paragraphs.slice(0, PREVIEW_PARAGRAPHS).join('\n\n')
    : profile.description

  return (
    <section className="about-section tour-about">
      <div className="about-section__inner">
        <div className="about-section__image-panel">
          <img
            src={`${API_BASE_URL}${profile.profileImage.url}`}
            alt={profile.name}
            className="about-section__photo"
          />
        </div>
        <div className="about-section__text-panel">
          <h2 className="about-section__heading headline-lg">
            About <span className="about-section__accent">{profile.firstName}</span>
          </h2>
          <div className="about-section__description body-lg">
            <ReactMarkdown>{visibleMarkdown}</ReactMarkdown>
          </div>
          {isTruncatable && (
            <button
              type="button"
              className="about-section__read-more"
              onClick={() => setExpanded(prev => !prev)}
              aria-expanded={expanded}
            >
              {expanded ? 'Read less' : 'Read more'}
            </button>
          )}
          <button type="button" className="button button--primary about-section__cta" onClick={onContact}>
            Get In Touch
          </button>
        </div>
      </div>
    </section>
  )
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `cd frontend && npm test -- AboutSection`
Expected: PASS, all 4 cases green.

- [ ] **Step 5: Add the toggle button style**

Append to the end of `frontend/src/styles.css`:

```css
.about-section__read-more {
  align-self: flex-start;
  margin: 0 0 1rem;
  padding: 0.4rem 0;
  background: transparent;
  border: none;
  color: var(--primary);
  font-family: inherit;
  font-size: 0.95rem;
  font-weight: 600;
  cursor: pointer;
  text-decoration: underline;
}

.about-section__read-more:hover {
  text-decoration: none;
}

@media (min-width: 769px) {
  .about-section__read-more {
    display: none;
  }
}
```

- [ ] **Step 6: Run the full frontend test suite**

Run: `cd frontend && npm test -- --run`
Expected: PASS — no other suite regressed.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/home/AboutSection.tsx frontend/tests/components/home/AboutSection.test.tsx frontend/src/styles.css
git commit -m "feat: add mobile Read more toggle to AboutSection"
```

---

### Task 7: End-to-end visual verification

Confirm everything composes correctly on a real phone-sized viewport and that desktop is untouched.

- [ ] **Step 1: Start the full stack**

Run: `./scripts/start.sh`
Wait until backend is on `http://localhost:8080` and frontend is on `http://localhost:5173`.

- [ ] **Step 2: Mobile viewport check**

Use Playwright MCP at 390×844 against `http://localhost:5173/`. Capture a full-page screenshot and verify:

- Top nav: search input + hamburger both 44px tall, vertical centers within 1px.
- Hero: contains badge, name, single-line tagline, chat input. **No** chips, **no** "Download CV" button, **no** social icons inside `.hero`.
- Profile photo block visible immediately after hero.
- Bio: only the first two paragraphs visible, "Read more" button present. Click it — remaining paragraphs appear, button label flips to "Read less". Click again — collapses.
- New "Connect" strip visible below the bio, containing a full-width "Download CV" button and three social icons.
- CTA section ("Let's build the impossible together") still visible at the bottom.

- [ ] **Step 3: Desktop viewport check**

Resize Playwright to 1280×800 and reload. Verify:

- Hero still contains chat-intro paragraph, suggested chips, CV button, and social icons (the demoted-on-mobile elements).
- `.connect-strip` is `display: none` (use `getComputedStyle`).
- About section shows the full bio with no "Read more" button.

- [ ] **Step 4: Re-run all frontend tests one final time**

Run: `cd frontend && npm test -- --run`
Expected: PASS.

- [ ] **Step 5: Final commit (only if any tweaks were needed during verification — otherwise skip)**

```bash
git add -p
git commit -m "fix: post-verification mobile layout tweaks"
```
