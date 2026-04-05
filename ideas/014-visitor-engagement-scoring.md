# 014: AI Visitor Engagement Scoring

## Summary
Use AI to analyse visitor behaviour patterns and assign engagement scores. Track which visitors (anonymised) are highly engaged (reading multiple posts, using chat, visiting experience page) vs. bouncing. Use these insights to optimize content placement and site structure.

## Why
Not all visitors are equal. A recruiter spending 10 minutes reading blog posts and checking the experience page is far more valuable than a bot bouncing after 2 seconds. Understanding engagement patterns helps prioritize which content to create and how to structure the site.

## Features
- **Engagement Score** - Per-session score based on: pages viewed, time on site, chat usage, scroll depth
- **Visitor Segments** - Auto-categorise: casual browser, content reader, potential recruiter, bot/crawler
- **Segment Dashboard** - Admin panel showing visitor segment breakdown over time
- **Content Performance** - Which blog posts attract high-engagement visitors
- **AI Insights** - Weekly AI-generated summary: "This week, 15% of visitors engaged with chat. Blog post X attracted the most recruiter-like visitors."

## Technical Approach
- Frontend: Lightweight event tracking (page views, scroll depth, time on page, chat opens) sent to a backend analytics endpoint
- Backend: `VisitorAnalyticsService` that scores sessions and persists to MongoDB
- Scoring model: weighted combination of behaviours (configurable)
- AI summary: Weekly cron job using Spring AI to generate natural language insights from aggregated data
- Admin page: `/admin/engagement` with charts showing segments and trends

## Complexity
Medium-High. The event tracking, scoring model, and AI summary each have moderate complexity. Privacy considerations are important.

## Dependencies
- MongoDB for analytics storage
- Spring AI for insight generation
- Charting library (shared with ideas 005, 009)
