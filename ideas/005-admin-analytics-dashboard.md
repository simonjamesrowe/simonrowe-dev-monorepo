# 005: Admin Analytics Dashboard

## Summary
Build a rich analytics dashboard in the admin console that aggregates data from multiple sources: Google Analytics (via GA4 Data API), AI chat usage metrics, and application-level metrics from Prometheus. Replace the current basic dashboard with actionable insights about visitor behaviour, content performance, and system health.

## Why
The current admin dashboard is minimal. Having visitor analytics, content performance, and system health in one place saves switching between GA, Grafana, and application logs. It makes the admin console a true command centre.

## Dashboard Panels
1. **Visitor Overview** - Unique visitors, page views, sessions (from GA4 API)
2. **Top Content** - Most viewed blog posts, most visited pages
3. **Traffic Sources** - Referral sources, search terms, social media
4. **AI Chat Metrics** - Total conversations, messages per session, popular questions, tool usage breakdown
5. **Content Stats** - Total blogs (published/draft), last published date, tags distribution
6. **System Health** - Backend uptime, response times (p50/p95/p99), error rates (from Prometheus)
7. **Search Analytics** - Top search queries, zero-result searches

## Technical Approach
- Backend: New `AdminAnalyticsController` with endpoints aggregating data from:
  - GA4 Data API (via Google Analytics Data Java client library)
  - MongoDB aggregation queries for chat session stats
  - Prometheus query API for system metrics
- Frontend: Dashboard page with chart components (use Recharts or Chart.js)
- Cache analytics data with TTL to avoid excessive API calls
- Date range picker for filtering

## Complexity
Medium-High. GA4 API integration requires Google Cloud credentials setup. Multiple data sources to aggregate.

## Dependencies
- Google Analytics Data API client library
- A charting library (Recharts recommended for React)
- Prometheus query API access
