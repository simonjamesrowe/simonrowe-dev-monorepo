# Feature Ideas for simonrowe.dev

A collection of feature ideas for expanding the personal website and admin CMS, with a focus on AI capabilities, analytics, and developer experience.

## AI & RAG

| # | Idea | Complexity | Description |
|---|------|-----------|-------------|
| 001 | [RAG-Enhanced AI Chat](./001-rag-enhanced-chat.md) | Medium-High | Vector embeddings + RAG pipeline to replace keyword search in the AI chat with semantic retrieval |
| 002 | [Embabel Content Agents](./002-embabel-content-agents.md) | High | Autonomous AI agents (via Rod Johnson's Embabel framework) for content workflows: drafting, reviewing, scheduling |
| 003 | [AI Blog Writing Assistant](./003-ai-blog-writing-assistant.md) | Medium | AI co-pilot in the MDX editor: inline completion, rewrite, code generation, style matching |
| 004 | [Semantic Blog Recommendations](./004-semantic-blog-recommendations.md) | Low-Medium | "Related Posts" powered by vector similarity instead of shared tags |
| 008 | [AI Auto-Tagging & Summarization](./008-ai-auto-tagging-summarization.md) | Low-Medium | Auto-generate tags, summaries, and meta descriptions for blog posts on save |
| 010 | [AI Image Alt-Text & Hero Generation](./010-ai-image-generation.md) | Medium | Vision model for auto alt-text; image generation for blog hero banners |
| 012 | [AI SEO Optimizer](./012-ai-seo-optimizer.md) | Medium | SEO scoring panel in the editor: keyword analysis, readability, JSON-LD, Google preview |

## Admin Console & Observability

| # | Idea | Complexity | Description |
|---|------|-----------|-------------|
| 005 | [Admin Analytics Dashboard](./005-admin-analytics-dashboard.md) | Medium-High | Unified dashboard: GA4 data, AI chat metrics, content stats, system health |
| 007 | [Admin Activity Log](./007-admin-activity-log.md) | Medium | Audit trail of all content changes with diff views and timeline |
| 009 | [AI Chat Analytics](./009-ai-chat-analytics.md) | Medium | Conversation insights: topic clusters, popular questions, tool usage, drop-off analysis |
| 011 | [Real-Time Admin Notifications](./011-real-time-admin-notifications.md) | Medium | Notification bell with live alerts: contact submissions, chat activity, health warnings |
| 013 | [Structured Log Viewer](./013-structured-logging-viewer.md) | Medium | Real-time log tailing in admin console with level/component filters and special views |

## Analytics & UX

| # | Idea | Complexity | Description |
|---|------|-----------|-------------|
| 006 | [Hotjar Integration](./006-hotjar-integration.md) | Low | Free-tier heatmaps, session recordings, and feedback widgets for UX insights |
| 014 | [Visitor Engagement Scoring](./014-visitor-engagement-scoring.md) | Medium-High | AI-driven visitor segmentation and engagement scoring with weekly insight summaries |

## Suggested Build Order

A pragmatic sequence that builds foundations first:

1. **001 - RAG-Enhanced Chat** (foundation for 003, 004, 012)
2. **006 - Hotjar Integration** (quick win, immediate UX insights)
3. **008 - AI Auto-Tagging** (low complexity, high daily value)
4. **007 - Admin Activity Log** (foundational for admin improvements)
5. **013 - Structured Log Viewer** (immediate operational value)
6. **005 - Admin Analytics Dashboard** (builds on existing GA4 + Prometheus)
7. **004 - Semantic Blog Recommendations** (depends on 001's vector store)
8. **009 - AI Chat Analytics** (persist conversations, then analyse)
9. **003 - AI Blog Writing Assistant** (leverage vector store from 001)
10. **011 - Real-Time Admin Notifications** (extend existing WebSocket infra)
11. **012 - AI SEO Optimizer** (nice-to-have, builds on 001 + 008)
12. **010 - AI Image Generation** (independent but lower priority)
13. **014 - Visitor Engagement Scoring** (needs analytics foundation from 005/006)
14. **002 - Embabel Content Agents** (most ambitious, benefits from all prior work)
