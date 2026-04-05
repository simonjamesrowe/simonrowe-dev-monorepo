# 009: AI Chat Analytics and Conversation Insights

## Summary
Build a dedicated analytics view for the AI chat feature in the admin console. Track conversation patterns, popular topics, tool usage, response quality signals, and visitor engagement metrics. Use AI to periodically summarise conversation trends.

## Why
The AI chat is a flagship feature of the site but currently has no visibility into how visitors use it. Understanding what visitors ask about, which tools get invoked, and where conversations drop off helps improve the chat experience and identify content gaps.

## Metrics to Track
- **Volume**: Conversations per day/week, messages per conversation
- **Topics**: Cluster conversations by topic (using embeddings)
- **Tool Usage**: Which MCP tools are called most (getProfile, searchBlogs, etc.)
- **Drop-off**: Where in conversations visitors stop engaging
- **Popular Questions**: Most frequently asked question types
- **Response Quality**: Average conversation length as a proxy for engagement
- **Rate Limiting**: How often visitors hit the rate limit

## Features
- **Chat Dashboard Panel** - Key metrics with sparkline trends
- **Conversation Browser** - Browse anonymised conversation transcripts
- **Topic Clusters** - AI-generated topic groupings (weekly summary)
- **Question Cloud** - Word cloud of common question themes
- **Alerts** - Notify if error rate spikes or unusual patterns emerge

## Technical Approach
- Backend: Extend chat session tracking to persist conversations to MongoDB (currently in-memory)
- New `ChatAnalyticsService` with aggregation queries
- Weekly cron job using Spring AI to cluster and summarise conversation topics
- Frontend: New admin page `/admin/chat-analytics` with charts and conversation browser
- Anonymise all data (no IP addresses or identifying info stored)

## Complexity
Medium. Persisting conversations is straightforward; the topic clustering and trend analysis add AI complexity.

## Dependencies
- MongoDB (for conversation persistence)
- Charting library (shared with idea 005)
- Spring AI for topic clustering
