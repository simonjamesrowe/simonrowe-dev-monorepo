# Quickstart: Embabel News & Events Aggregation

**Feature**: 021-embabel-news-events

## Prerequisites

- Java 21
- Running MongoDB, Elasticsearch, Kafka (via `docker-compose up`)
- `OPENAI_API_KEY` environment variable set
- Backend and frontend dev environment (see `scripts/start.sh`)

## New Dependencies

### Backend (build.gradle.kts)

```kotlin
// Embabel agent framework
implementation("com.embabel.agent:embabel-agent-starter:0.3.5")
implementation("com.embabel.agent:embabel-agent-starter-openai:0.3.5")

// Web scraping
implementation("org.jsoup:jsoup:1.18.3")

// RSS parsing
implementation("com.rometools:rome:2.1.0")
```

Note: Embabel requires the Embabel Maven repository:
```kotlin
repositories {
    maven { url = uri("https://repo.embabel.com/artifactory/embabel-releases") }
}
```

### GraalVM Native Image Impact

Embabel is **incompatible** with GraalVM native image due to kotlin-reflect dependency. The backend must run in JVM mode while Embabel is included. This is a documented constitution violation (see plan.md Complexity Tracking).

## Key Files to Create

### Backend

```
backend/src/main/java/com/simonrowe/
├── aggregation/
│   ├── AggregatedArticle.java          # MongoDB entity
│   ├── AggregatedArticleRepository.java
│   ├── AggregatedEvent.java            # MongoDB entity
│   ├── AggregatedEventRepository.java
���   ├── ContentSource.java              # MongoDB entity
│   ├── ContentSourceRepository.java
│   ├── NewsController.java             # GET /api/news, /api/news/{id}
│   ├── EventsController.java           # GET /api/events, /api/events/{id}
│   ├── AdminAggregationController.java # Admin CRUD + manual triggers
│   └── AggregationScheduler.java       # @Scheduled trigger for agents
├── agents/
│   ├── ContentAggregationAgent.java    # Embabel @Agent: fetch→parse→summarize→store
│   ├── WeeklyDigestAgent.java          # Embabel @Agent: gather activity→summarize→publish
│   ├── actions/
│   │   ├── FetchContentAction.java     # @Action: fetch from sources
│   │   ├── ParseContentAction.java     # @Action: extract articles/events from HTML/RSS
│   │   ├── SummarizeContentAction.java # @Action: LLM summarization
│   │   ├── StoreContentAction.java     # @Action: save to MongoDB + publish Kafka events
│   │   ├── GatherActivityAction.java   # @Action: collect week's blogs, news, commits
│   │   └── PublishDigestAction.java    # @Action: create blog post from digest
│   └── scrapers/
│       ├── RssScraper.java             # RSS/Atom feed parser (Rome)
│       ├── SitemapHtmlScraper.java      # Sitemap discovery + JSoup HTML scraper
│       └── ScraperFactory.java          # Factory based on ContentSource.scrapeStrategy
```

### Frontend

```
frontend/src/
├── types/
│   ├── news.ts                         # ArticleResponse, ArticlePage types
│   └── events.ts                       # EventResponse, EventPage types
├── services/
│   ├─��� newsApi.ts                      # fetchNews(), fetchNewsById()
│   └── eventsApi.ts                    # fetchEvents(), fetchEventsById()
├── pages/
│   ├── NewsPage.tsx                    # News listing page
│   └── EventsPage.tsx                  # Events listing page (upcoming/past sections)
```

### Files to Modify

```
backend:
  - build.gradle.kts                    # Add Embabel + JSoup + Rome dependencies
  - ContentChangeEvent.java             # Add AGGREGATED_ARTICLE, AGGREGATED_EVENT to ContentType
  - ContentChangeConsumer.java          # Add cases for new content types
  - EmbeddingChangeConsumer.java        # Add cases for new content types
  - EmbeddingService.java              # Add embedArticle(), embedEvent(), embedAllArticles(), embedAllEvents()
  - IndexService.java                  # Add converter methods and sync for news/events
  - SearchService.java                 # Add news/events to grouped search
  - SearchController.java             # Update GroupedSearchResponse
  - ProfileMcpTools.java              # Add searchNews(), getUpcomingEvents() tools

frontend:
  - App.tsx                            # Add /news and /events routes
  - TopNav.tsx                         # Add News and Events nav links
  - styles.css                         # Add news/events page styles
  - types/search.ts                    # Update GroupedSearchResponse type
  - components/search/SiteSearch.tsx   # Handle news/event result types
```

## Environment Variables

No new environment variables required. Uses existing `OPENAI_API_KEY`.

Optional configuration (application.yml):
```yaml
aggregation:
  schedule:
    cron: "0 0 */6 * * *"    # Every 6 hours (default)
  digest:
    cron: "0 0 8 * * MON"    # Monday 8am (default)
```

## Manual Testing

1. Start backend + frontend: `./scripts/start.sh`
2. Trigger aggregation manually: `POST /api/admin/aggregation/trigger` (with JWT)
3. Verify articles appear: `GET /api/news`
4. Verify events appear: `GET /api/events`
5. Check search integration: `GET /api/search?q=spring`
6. Test chatbot: Ask "What's the latest tech news?"
7. Trigger digest: `POST /api/admin/digest/trigger`
8. Verify digest blog post: `GET /api/blogs`
