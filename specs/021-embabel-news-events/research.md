# Research: Embabel News & Events Aggregation

**Feature**: 021-embabel-news-events  
**Date**: 2026-04-12

## R1: Embabel Framework + GraalVM Native Image Compatibility

**Decision**: Run the backend in JVM mode (not native image) when Embabel is included, with a documented constitution violation justification.

**Rationale**: Embabel 0.3.5 depends on `kotlin-reflect` for annotation scanning (`@Agent`, `@Action`, `@AchievesGoal`) and runtime type introspection. It provides zero GraalVM metadata (`reflect-config.json`, `resource-config.json`, etc.). Native image compilation would fail without significant upstream work that doesn't exist yet. Since the user explicitly requested Embabel, and native image is a constitution requirement, this is a justified tradeoff: Embabel's agentic orchestration value outweighs the native image performance benefit for this feature.

**Alternatives considered**:
- Build agentic flows without Embabel using plain Spring AI + `@Scheduled` — rejected because user explicitly requested Embabel framework
- Run Embabel in a separate microservice — rejected as it violates the monorepo single-backend constitution principle and adds operational complexity disproportionate to the benefit
- Wait for Embabel to add GraalVM support — rejected as timeline unknown (framework is at v0.3.5, early stage)

## R2: Embabel + Spring AI Coexistence

**Decision**: Use Embabel's `embabel-agent-starter` + `embabel-agent-starter-openai` alongside existing Spring AI 1.1.4 dependencies. Pin Spring AI version explicitly to avoid BOM conflicts.

**Rationale**: Embabel uses Spring AI internally (not a separate OpenAI SDK). Both the existing codebase and Embabel use `spring-ai-openai` through Spring AI's `ChatModel` abstraction. Version alignment is manageable since both target Spring AI 1.x on Spring Boot 3.5.x. The existing `OPENAI_API_KEY` environment variable will be shared.

**Alternatives considered**:
- Use Embabel's Anthropic starter instead — rejected because constitution mandates OpenAI as sole LLM provider
- Isolate Embabel's AI calls from existing Spring AI — unnecessary since they share the same abstraction

## R3: Content Source Feed Availability

**Decision**: Use a mixed strategy per source — RSS where available, sitemap+HTML scraping elsewhere.

**Rationale**: Only Spring Blog has a reliable RSS feed. The other sources require HTML scraping.

| Source | Strategy | Feed/Entry Point |
|--------|----------|-----------------|
| Spring Blog | RSS feed parsing | `https://spring.io/blog.atom` (RSS 2.0) |
| AI Native Dev | Sitemap + HTML scraping | `https://ainativedev.io/sitemap.xml` (400+ URLs) |
| Rundown AI | Sitemap + HTML scraping | `https://www.rundown.ai/sitemap.xml` (1400+ URLs) |
| London Java Community | RSS polling + HTML fallback | `https://www.meetup.com/londonjavacommunity/events/rss/` (may be empty when no upcoming events) |

**Alternatives considered**:
- Meetup GraphQL API — rejected, requires paid Pro subscription
- Headless browser for all sources — rejected as overkill; only needed if JS-rendered content can't be extracted from HTML source. Spring Blog RSS avoids scraping entirely.

## R4: Web Scraping Library Selection

**Decision**: Use JSoup for HTML parsing and Rome/ROME for RSS feed parsing. Use Playwright (via Embabel's web tool group) only as fallback for JS-rendered pages that can't be parsed from static HTML.

**Rationale**: JSoup is a mature, well-tested Java HTML parser that handles most static web pages. Rome is the standard Java RSS/Atom parser. Both are lightweight and GraalVM-compatible (though GraalVM is deferred per R1). Embabel's `CoreToolGroups.WEB` provides built-in web fetching capabilities that can be used within agent actions.

**Alternatives considered**:
- Selenium/Playwright for all scraping — rejected as heavyweight; unnecessary for sites with static HTML or RSS
- Spring WebClient only — insufficient for HTML parsing; need JSoup for DOM traversal

## R5: Embabel Agent Architecture

**Decision**: Create two Embabel agents: (1) `ContentAggregationAgent` for scheduled scraping + summarization, (2) `WeeklyDigestAgent` for weekly summary generation. Both triggered by Spring `@Scheduled` methods that invoke the Embabel agent platform.

**Rationale**: Embabel agents orchestrate multi-step workflows with LLM interactions. The aggregation flow (fetch → parse → summarize → store → index) maps naturally to Embabel's action sequencing. The weekly digest (gather activity → summarize → publish) is a separate concern. Spring `@Scheduled` triggers agent execution since Embabel doesn't have built-in scheduling.

**Alternatives considered**:
- Single monolithic agent — rejected; aggregation and digest have different schedules and concerns
- Three agents (scrape, summarize, digest) — rejected as over-decomposed; scrape+summarize are tightly coupled steps in a single flow

## R6: Data Model Integration with Existing Patterns

**Decision**: Follow the existing MongoDB entity + Kafka event + Elasticsearch index + vector embedding pattern. Add `NEWS` and `EVENT` to the `ContentType` enum. Create new MongoDB collections, Elasticsearch index entries, and vector embeddings following established conventions.

**Rationale**: The codebase has a well-established pattern: entity → repository → controller → Kafka event → search index → vector embedding. Following this pattern minimizes risk and ensures aggregated content integrates seamlessly with existing search and chatbot features.
