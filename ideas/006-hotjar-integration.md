# 006: Hotjar Integration for UX Insights

## Summary
Integrate Hotjar's free tier into the public-facing frontend to capture heatmaps, session recordings, and visitor feedback. This provides qualitative UX data that complements the quantitative data from Google Analytics.

## Why
GA4 tells you *what* visitors do (page views, bounce rates) but not *how* they interact with the page. Hotjar heatmaps show where visitors click, scroll, and hover. Session recordings reveal navigation patterns, confusion points, and engagement with the AI chat. This is especially valuable for understanding how visitors interact with the chat module and whether they explore beyond the homepage.

## Features
- **Heatmaps** - Click, scroll, and move heatmaps for all pages
- **Session Recordings** - Watch real visitor sessions (free tier: 35 sessions/day)
- **Feedback Widget** - Optional "Was this helpful?" widget on blog posts
- **Funnel Analysis** - Track visitor flow: Home -> Experience/Blog -> Chat -> Contact
- **Admin Integration** - Link to Hotjar dashboard from admin console analytics page

## Technical Approach
- Add Hotjar tracking script to `index.html` or via a React component
- Configure via `VITE_HOTJAR_SITE_ID` environment variable
- Conditionally load only in production (skip in dev/local)
- Respect visitor privacy: exclude admin routes from recording
- Add a privacy notice in the site footer about analytics/recording
- Optional: Use Hotjar's Identify API to tag chat users (anonymous ID only)

## Complexity
Low. Hotjar free tier setup is just a script tag. The main work is deciding what to track and adding a privacy notice.

## Free Tier Limits
- 35 daily sessions for recordings
- Unlimited heatmaps
- Incoming feedback widgets
- No credit card required

## Dependencies
- Hotjar account (free tier)
- Privacy/cookie notice update
