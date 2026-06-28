# UI and Tour Contract: Landing Profile Split

## Public Routes

| Route | Required Content |
|-------|------------------|
| `/` | Centered chat-first hero, prompt chips, `.tour-home-chat`, footer after hero |
| `/profile` | Profile overview, biography/profile summary, CV/social actions, contact details/form |
| `/profile#contact` | Same as `/profile`, with a stable `#contact` target |

## Navigation Contract

| Surface | Required Link |
|---------|---------------|
| Desktop top nav | `Profile` routes to `/profile` |
| Mobile menu | `Profile` routes to `/profile` |
| Footer Explore | `Profile` routes to `/profile` |
| Footer Connect | `Contact` routes to `/profile#contact` |

## Required Tour Targets

| Order | Route | Selector | Purpose |
|-------|-------|----------|---------|
| 1 | `/` | `.tour-home-chat` | Home hero/chat composer |
| 2 | `/` | `.tour-search` | Site search |
| 3 | `/` | `.top-nav__ask-ai` | Ask AI nav action |
| 4 | `/profile` | `.tour-profile` | Profile overview |
| 5 | `/profile#contact` | `.tour-contact` | Profile contact section |
| 6 | `/experience` | `.tour-experience` | Experience overview |
| 7 | `/blogs` | `.tour-blogs` | Blog listing |
| 8 | `/news-events` | `.tour-news-events` | News & Events |

## Tour Action Contract

- `.tour-search` focuses or opens site search using the existing search simulation behavior.
- `.top-nav__ask-ai` opens chat with the existing Ask AI behavior.
- `.tour-contact` performs no drawer click action.
- `.tour-contact` performs no drawer cleanup action.
- Existing experience drawer actions remain unchanged.
