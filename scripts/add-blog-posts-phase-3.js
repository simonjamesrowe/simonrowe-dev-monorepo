// Migration script: Add Phase 3 blog posts and tags
// Runs via mongosh inside the MongoDB container
// Idempotent — checks for existing documents before inserting

const db = db.getSiblingDB('simonrowe');

// ============================================================================
// Helper functions
// ============================================================================

function findOrCreateTag(name) {
  let tag = db.tags.findOne({ name: { $regex: new RegExp('^' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$', 'i') } });
  if (tag) {
    print('  Tag already exists: ' + name + ' (' + tag._id + ')');
    return tag._id;
  }
  const now = new Date();
  const result = db.tags.insertOne({
    name: name,
    createdAt: now,
    updatedAt: now,
    _class: 'com.simonrowe.admin.Tag'
  });
  print('  Created tag: ' + name + ' (' + result.insertedId + ')');
  return result.insertedId;
}

function findSkillId(name) {
  const skill = db.skills.findOne({ name: { $regex: new RegExp('^' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$', 'i') } });
  if (skill) {
    return skill._id;
  }
  print('  WARNING: Skill not found: ' + name + ' — skipping');
  return null;
}

function buildDbRefArray(collection, ids) {
  return ids.filter(id => id !== null).map(id => ({
    '$ref': collection,
    '$id': id
  }));
}

// ============================================================================
// Idempotency check
// ============================================================================

const existingPost = db.blogs.findOne({ title: 'Fixing AI Hallucinations: A Conversation-Aware RAG Advisor' });
if (existingPost) {
  print('--- Phase 3 blog posts already exist (found RAG advisor post). Skipping. ---');
  quit();
}

print('=== Adding Phase 3 blog posts ===');
print('');

// ============================================================================
// Phase 1: Create new tags
// ============================================================================

print('--- Creating new tags ---');

const tagIds = {};
const newTags = ['RAG (Retrieval-Augmented Generation)', 'Web Scraping', 'Content Aggregation', 'Agents', 'Embabel', 'Kafka'];

newTags.forEach(name => {
  tagIds[name] = findOrCreateTag(name);
});

// Also look up existing tags we need
const existingTags = ['Spring AI', 'AI', 'Spring Boot'];
existingTags.forEach(name => {
  tagIds[name] = findOrCreateTag(name);
});

print('');

// ============================================================================
// Phase 2: Look up existing skills
// ============================================================================

print('--- Looking up skills ---');

const skillIds = {};
const skillNames = ['Java 21', 'Spring Boot', 'React', 'Typescript'];

skillNames.forEach(name => {
  skillIds[name] = findSkillId(name);
});

print('');

// ============================================================================
// Phase 3: Blog post content
// ============================================================================

// ---------------------------------------------------------------------------
// Post 9: Fixing AI Hallucinations
// ---------------------------------------------------------------------------

const BLOG_9_CONTENT = `The AI chatbot on my portfolio site was working well — visitors could ask about my skills, experience, and blog posts, and it would use MCP tools to look things up and respond conversationally. But there was a problem: follow-up questions produced terrible results.

## The Problem with Standard RAG

The site uses [Spring AI](https://spring.io/projects/spring-ai) with a vector store (Elasticsearch) for retrieval-augmented generation. The standard \`QuestionAnswerAdvisor\` works like this: take the user's message, search the vector store for similar documents, inject those documents as context into the prompt, and let the LLM answer.

This works perfectly for standalone questions. Ask "What technologies does Simon use?" and the vector search finds relevant skill and blog documents. But conversations aren't standalone questions.

When a visitor asks "Tell me more about that" or "What about the testing approach?" — the vector search has almost nothing to work with. It searches for "tell me more about that" and gets back random, irrelevant documents. The LLM then either hallucinates an answer or gives a generic non-response.

## Building a Conversation-Aware Advisor

The fix was to replace \`QuestionAnswerAdvisor\` with a custom \`ContextAwareQuestionAnswerAdvisor\` that enriches vector searches with conversation history. Instead of searching with just the current message, it concatenates recent user messages to build a richer query:

\`\`\`java
private String buildEnrichedQuery(
    final Map<String, Object> context, final String currentMessage) {
  Object conversationId = context.get(ChatMemory.CONVERSATION_ID);
  if (conversationId == null) {
    return currentMessage;
  }

  List<Message> history = chatMemory.get(conversationId.toString());
  if (history == null || history.isEmpty()) {
    return currentMessage;
  }

  List<String> recentUserMessages = history.stream()
      .filter(message -> message instanceof UserMessage)
      .map(Message::getText)
      .collect(Collectors.toCollection(ArrayList::new));

  int fromIndex = Math.max(0, recentUserMessages.size() - historySize);
  List<String> selectedMessages =
      recentUserMessages.subList(fromIndex, recentUserMessages.size());

  if (selectedMessages.isEmpty()) {
    return currentMessage;
  }

  return String.join(" ", selectedMessages) + " " + currentMessage;
}
\`\`\`

Now when a visitor asks "What technologies does Simon use?" followed by "Tell me more about that", the vector search query becomes "What technologies does Simon use? Tell me more about that" — which retrieves the same relevant documents as the original question.

The \`historySize\` parameter (default 5) controls how many prior messages to include. Too few and you lose context; too many and the search query becomes noisy.

## Filtering Noisy Document Types

The second hallucination source was more subtle. I'd recently added a code examples feature — snippets of real code from the portfolio that visitors could browse. These code examples were embedded in the vector store alongside blog posts and profile data.

The problem? When the LLM received raw code snippets as context, it would sometimes reference them as if they were blog posts, or fabricate URLs to code examples that didn't exist. The code examples were useful for the code browsing feature but poisonous for the chatbot.

The fix was a one-line filter:

\`\`\`java
List<Document> documents = vectorStore.similaritySearch(enrichedSearchRequest).stream()
    .filter(doc -> !"code_example".equals(doc.getMetadata().get("sourceType")))
    .toList();
\`\`\`

By excluding \`code_example\` documents from the RAG context, the chatbot only sees blog posts, profile data, and job history — content types that have natural-language descriptions and real URLs.

## Structured Metadata for Source Attribution

The third improvement was giving the LLM enough metadata to cite its sources properly. Instead of injecting raw document text into the prompt, each document gets a structured header:

\`\`\`java
private String formatDocumentWithMetadata(final Document doc) {
  Map<String, Object> metadata = doc.getMetadata();
  StringBuilder header = new StringBuilder("[Source:");
  Object title = metadata.get("title");
  if (title != null) {
    header.append(" ").append(title);
  }
  Object url = metadata.get("url");
  if (url != null) {
    header.append(" | URL: ").append(url);
  }
  Object sourceType = metadata.get("sourceType");
  if (sourceType != null) {
    header.append(" | Type: ").append(sourceType);
  }
  header.append("]");
  return header + System.lineSeparator() + doc.getText();
}
\`\`\`

This produces context blocks like:

\`\`\`
[Source: Adding AI Chat to My Portfolio | URL: /blogs/adding-ai-chat | Type: blog]
What if visitors to your portfolio site could just ask questions...
\`\`\`

Now the LLM can reference actual blog titles and link to real URLs instead of guessing. The \`sourceType\` field helps it understand what kind of content it's looking at — a blog post vs. a job description vs. a skill summary.

## The Prompt Template

The advisor uses a straightforward prompt template that wraps the user's original question with the retrieved context:

\`\`\`java
private static final String PROMPT_TEMPLATE =
    "{query}"
        + "\\n\\nContext information is below, surrounded by ---------------------"
        + "\\n\\n---------------------"
        + "\\n{question_answer_context}"
        + "\\n---------------------"
        + "\\n\\nGiven the context and provided history information and not prior knowledge,"
        + "\\nreply to the user comment. If the answer is not in the context, inform"
        + "\\nthe user that you can't answer the question.";
\`\`\`

The key instruction is "not prior knowledge" — this grounds the LLM in the retrieved documents rather than its training data. When combined with the structured metadata, it produces responses that are both accurate and properly attributed.

## Implementing as a Spring AI BaseAdvisor

The advisor implements Spring AI's \`BaseAdvisor\` interface, which provides \`before\` and \`after\` hooks around the chat client call:

\`\`\`java
@Override
public ChatClientRequest before(
    final ChatClientRequest request, final AdvisorChain chain) {
  String currentMessage = request.prompt().getUserMessage().getText();
  String enrichedQuery = buildEnrichedQuery(request.context(), currentMessage);

  SearchRequest enrichedSearchRequest =
      SearchRequest.builder()
          .query(enrichedQuery)
          .similarityThreshold(searchRequest.getSimilarityThreshold())
          .topK(searchRequest.getTopK())
          .build();

  List<Document> documents = vectorStore.similaritySearch(enrichedSearchRequest).stream()
      .filter(doc -> !"code_example".equals(doc.getMetadata().get("sourceType")))
      .toList();

  String contextText = documents.stream()
      .map(this::formatDocumentWithMetadata)
      .collect(Collectors.joining("\\n\\n"));

  String renderedPrompt = PROMPT_TEMPLATE
      .replace("{query}", currentMessage)
      .replace("{question_answer_context}", contextText);

  return request.mutate()
      .prompt(request.prompt().augmentUserMessage(renderedPrompt))
      .build();
}
\`\`\`

The enriched query is only used for the vector search — the original user message is preserved in the prompt template via \`{query}\`, so the LLM sees the actual question the visitor asked.

## What I Learned

RAG quality comes from what you exclude as much as what you include. The three changes — conversation-aware search, source type filtering, and structured metadata — each addressed a different failure mode:

1. **Conversation history enrichment** fixed follow-up questions that had no context
2. **Source type filtering** stopped code examples from confusing the LLM
3. **Structured metadata** gave the LLM real titles and URLs to cite

None of these required changing the LLM model or the system prompt. The chatbot's personality and knowledge stayed the same — it just got better inputs to work with.`;

// ---------------------------------------------------------------------------
// Post 10: Automated News Aggregation
// ---------------------------------------------------------------------------

const BLOG_10_CONTENT = `A portfolio site that only shows your own content feels static. What if it also curated relevant tech news and upcoming events — automatically? That's the idea behind the content aggregation system I built. It uses [Embabel](https://embabel.com) agents to orchestrate a pipeline that scrapes external sources, classifies each piece with an LLM, stores everything locally, and publishes events that trigger downstream search indexing and vector embedding for the site's AI chatbot.

## Why Embabel

The first version of this pipeline used Spring AI's \`ChatClient\` directly — building prompts, calling the model, parsing the response. It worked, but the code was a tangle of scraping logic, LLM calls, and storage operations with no clear boundaries.

[Embabel](https://embabel.com) is an agentic framework for Spring that brings structure to AI-powered workflows. It provides:

- **\`@Agent\`** — declares a class as an autonomous agent with a name and description
- **\`@Action\`** — marks methods as discrete, describable operations the agent can perform
- **\`Ai\`** — a clean abstraction over LLM calls that handles structured output natively

Switching to Embabel meant the pipeline became two well-defined agents with clear responsibilities, rather than a monolithic service with scattered AI calls.

![Embabel Agent Architecture](/uploads/17b473e4-e028-4262-8fb9-0965b0a1b879/original.jpg)

## The Two Agents

### ContentAggregationAgent

This agent handles the full scrape-classify-store pipeline:

\`\`\`java
@Agent(
    name = "ContentAggregation",
    description = "Scrapes external content sources, classifies items "
        + "using an LLM, and stores articles and events locally"
)
public class ContentAggregationAgent {

  private final Ai ai;
  // ... repositories, scrapers, publishers

  @Action(description = "Aggregate content from all active sources")
  public void runAggregation() {
    List<ContentSource> sources = sourceRepository.findByActiveTrue();
    for (ContentSource source : sources) {
      try {
        processSource(source);
        sourceRepository.save(source.withLastFetchedAt(Instant.now()));
      } catch (Exception e) {
        log.error("Failed to process source: {}", source.name(), e);
        sourceRepository.save(source.withLastError(e.getMessage()));
      }
    }
  }

  @Action(description = "Import a single article or event from a URL")
  public String importFromUrl(final String url) {
    // Scrape, classify, and store a single URL on demand
  }
}
\`\`\`

The \`@Agent\` annotation declares metadata that Embabel uses for orchestration and observability. Each \`@Action\` is a self-contained operation — \`runAggregation()\` processes all active sources on a schedule, while \`importFromUrl()\` handles ad-hoc imports triggered from the admin UI or via an MCP tool.

### WeeklyDigestAgent

A second agent generates a weekly summary blog post from recent content:

\`\`\`java
@Agent(
    name = "WeeklyDigest",
    description = "Generates a weekly digest blog post summarising "
        + "recent site activity and aggregated tech news"
)
public class WeeklyDigestAgent {

  private final Ai ai;

  @Action(description = "Generate a digest blog post")
  public void generateDigest() {
    // Gather recent blogs + aggregated articles
    // Generate markdown via LLM
    // Create and publish blog post
  }
}
\`\`\`

The digest agent reads from the same \`AggregatedArticle\` collection that the aggregation agent writes to, creating a natural producer-consumer relationship between the two agents.

## The Scraper Architecture

Content sources are configured in MongoDB — each \`ContentSource\` record defines a name, URL, and scraping strategy. A \`ScraperFactory\` routes each source to the right implementation using a Java 21 switch expression:

\`\`\`java
public List<ScrapedContent> scrape(ContentSource source) {
  boolean isEvent = source.sourceType() == ContentSource.SourceType.EVENTS;
  return switch (source.scrapeStrategy()) {
    case RSS -> rssScraper.scrape(source.feedUrl(), isEvent);
    case SITEMAP_HTML -> sitemapHtmlScraper.scrape(source.sitemapUrl());
    case HTML -> sitemapHtmlScraper.scrapeEventsPage(source.baseUrl());
    case HTML_LISTING -> sitemapHtmlScraper.scrapeListingPage(source.baseUrl());
    case LUMA -> lumaApiScraper.scrape(source.feedUrl());
  };
}
\`\`\`

Five strategies cover most tech content sources:

- **RSS** — Rome library parses RSS/Atom feeds (Spring Blog, London Java Community)
- **SITEMAP_HTML** — fetches a sitemap XML, then scrapes each page with JSoup
- **HTML** — scrapes event listing pages directly
- **HTML_LISTING** — scrapes article listing pages, following links to detail pages
- **LUMA** — hits the lu.ma calendar API for event listings with dates, venues, and locations

Each scraper returns a uniform \`ScrapedContent\` record regardless of the source format. Adding a new source is a data change in the admin UI, not a code change.

![Content Aggregation Pipeline](/uploads/62598943-002c-49fb-b9ba-811556e0db61/original.png)

## LLM Classification with Embabel's Ai Interface

Scraped content arrives as raw text — but is it a news article or an event? What's a good summary? Rather than writing brittle parsing rules, I ask an LLM. This is where Embabel's \`Ai\` abstraction shines:

\`\`\`java
ContentClassification classifyAndSummarize(final ScrapedContent content) {
  if (content.content() == null || content.content().length() < 50) {
    return new ContentClassification(
        content.isEvent() ? "event" : "article",
        content.title(), null, null, null, null);
  }
  try {
    String truncated = content.content().length() > 5000
        ? content.content().substring(0, 5000) : content.content();
    String prompt = String.format(
        CLASSIFY_PROMPT, content.url(), content.title(), truncated);
    return ai.withLlm("gpt-4o-mini")
        .creating(ContentClassification.class)
        .fromPrompt(prompt);
  } catch (Exception e) {
    log.warn("Classification failed for: {}. Using defaults.",
        content.title(), e);
  }
  return new ContentClassification(
      content.isEvent() ? "event" : "article",
      content.title(), null, null, null, null);
}
\`\`\`

Compare this to the old Spring AI approach — no \`ChatClient\` builder, no \`BeanOutputConverter\`, no manual JSON parsing. The Embabel \`Ai\` interface handles structured output natively: \`ai.withLlm("gpt-4o-mini").creating(ContentClassification.class).fromPrompt(prompt)\` tells the framework which model to use, what Java type to return, and what prompt to send. Embabel handles the schema generation and response parsing.

The \`ContentClassification\` record defines the structure the LLM returns:

\`\`\`java
public record ContentClassification(
    @JsonProperty("type") String type,
    @JsonProperty("summary") String summary,
    @JsonProperty("eventDate") String eventDate,
    @JsonProperty("venue") String venue,
    @JsonProperty("location") String location,
    @JsonProperty("publishedDate") String publishedDate
) {}
\`\`\`

The prompt asks the LLM to classify the content type, write a 2-3 sentence summary, extract event details if applicable, and find the published date. If classification fails, we fall back to sensible defaults rather than crashing.

## Event-Driven Downstream Processing

When an article or event is saved, a Kafka event is published to the \`content-changes\` topic. Two independent consumers react:

**Search Indexing** — \`ContentChangeConsumer\` listens for \`AGGREGATED_ARTICLE\` and \`AGGREGATED_EVENT\` events and indexes them into Elasticsearch. This means aggregated content appears in the site's unified search alongside blogs, jobs, and skills — with retry logic (4 attempts, exponential backoff) and a dead-letter topic for failures.

**Vector Embedding** — \`EmbeddingChangeConsumer\` embeds the content into the vector store for the AI chatbot. Article titles, summaries, and full content are chunked and embedded, so visitors can ask the chatbot about recent tech news and get answers grounded in real aggregated content.

This event-driven design means the aggregation agent doesn't need to know about search indexing or vector embedding. It just publishes an event and moves on. Adding a new consumer (e.g., sending a Slack notification for new events) is a new listener, not a change to the agent.

## The Weekly Digest

The \`WeeklyDigestAgent\` runs every Monday morning and generates a blog post summarising recent activity. It gathers new blog posts and aggregated articles since the last digest, builds a structured summary, and asks the LLM to write a friendly roundup:

\`\`\`java
private String generateDigestContent(final String activitySummary) {
  try {
    return ai.withLlm("gpt-4o-mini")
        .respond(List.of(
            new UserMessage(DIGEST_PROMPT + activitySummary)))
        .getContent();
  } catch (Exception e) {
    log.error("Failed to generate digest via LLM, using raw summary", e);
    return activitySummary;
  }
}
\`\`\`

The digest gets an AI-generated featured image, a "Weekly Digest" tag, and is auto-published — no manual approval needed. It also publishes a Kafka event, so it gets indexed for search and embedded in the vector store like any other content.

![Weekly Digest blog post](/uploads/0cb48869-88c7-4c6e-aa3a-34b6d2f9c3a4/original.png)

## The Frontend

The News & Events page shows aggregated content with source-based filtering, a hero layout for the latest items, and a card grid below:

![News & Events page](/uploads/7273bfae-bf07-4c39-816e-c3789d812404/original.png)

Filter buttons across the top let visitors focus on specific sources — Tessl Blog, Spring Blog, Rundown AI, or Events. Each card shows the title, source, AI-generated summary, publication date, and links to the original article. The page pulls from the same MongoDB collections that the aggregation agent writes to.

## Image Handling

External images are a liability — they can disappear, change, or slow down page loads. The \`ExternalImageDownloader\` fetches images from source URLs and stores them locally. If the source has no image, the \`BlogImageGenerationService\` generates one using AI. Either way, aggregated content gets the same treatment as uploaded media — the site never hot-links external resources.

## Scheduling

An \`AggregationScheduler\` triggers both agents on configurable cron schedules:

\`\`\`java
@Scheduled(cron = "\${aggregation.schedule.cron:0 0 */6 * * *}")
public void runScheduledAggregation() {
  aggregationAgent.runAggregation();
}

@Scheduled(cron = "\${aggregation.digest.cron:0 0 8 * * MON}")
public void runScheduledDigest() {
  digestAgent.generateDigest();
}
\`\`\`

Content aggregation runs every 6 hours. The digest runs Monday mornings. Both schedules are configurable via application properties.

## What I Learned

**Embabel simplifies AI orchestration.** The \`@Agent\` / \`@Action\` / \`Ai\` abstractions eliminated boilerplate around prompt building and response parsing. The structured output support (\`ai.creating(SomeRecord.class).fromPrompt(...)\`) is particularly clean — it replaces the manual \`BeanOutputConverter\` + \`ChatClient\` dance.

**Event-driven architecture pays off for side-effects.** Decoupling the aggregation pipeline from search indexing and vector embedding via Kafka means each concern evolves independently. The aggregation agent has no idea that a chatbot exists — it just publishes content change events.

**The scrape-classify-store pattern is reusable.** Five scraping strategies cover RSS feeds, sitemaps, HTML pages, listing pages, and API endpoints. The LLM handles classification and summarisation that would be fragile with rules. And MongoDB + local images means the site owns its data.`;

// ============================================================================
// Phase 4: Insert blog posts
// ============================================================================

print('--- Inserting blog posts ---');
print('');

// Post 9: RAG Advisor
const post9Tags = buildDbRefArray('tags', [
  tagIds['RAG (Retrieval-Augmented Generation)'], tagIds['Spring AI'],
  tagIds['AI'], tagIds['Spring Boot']
]);
const post9Skills = buildDbRefArray('skills', [
  skillIds['Java 21'], skillIds['Spring Boot'],
  skillIds['React'], skillIds['Typescript']
]);

const post9Result = db.blogs.insertOne({
  title: 'Fixing AI Hallucinations: A Conversation-Aware RAG Advisor',
  shortDescription: 'How I stopped my portfolio chatbot from hallucinating blog links and making up facts by building a conversation-aware vector search advisor that enriches queries with chat history and filters noisy document types.',
  content: BLOG_9_CONTENT,
  published: true,
  featuredImageUrl: '/uploads/blog-phase3-9-rag.jpg',
  tags: post9Tags,
  skills: post9Skills,
  createdDate: new Date('2026-04-11T10:00:00Z'),
  updatedDate: new Date('2026-04-11T10:00:00Z'),
  _class: 'com.simonrowe.admin.Blog'
});
print('Inserted Post 9: RAG Advisor (' + post9Result.insertedId + ')');

// Post 10: News Aggregation
const post10Tags = buildDbRefArray('tags', [
  tagIds['Web Scraping'], tagIds['Content Aggregation'],
  tagIds['Agents'], tagIds['Spring AI'], tagIds['AI'],
  tagIds['Spring Boot'], tagIds['Embabel'], tagIds['Kafka']
]);
const post10Skills = buildDbRefArray('skills', [
  skillIds['Java 21'], skillIds['Spring Boot'],
  skillIds['React'], skillIds['Typescript']
]);

const post10Result = db.blogs.insertOne({
  title: 'Automated News Aggregation with AI-Powered Classification',
  shortDescription: 'How I used Embabel agents to build an automated content aggregation pipeline — scraping RSS feeds, sitemaps, and event APIs, classifying content with an LLM, and publishing Kafka events for search indexing and vector embedding.',
  content: BLOG_10_CONTENT,
  published: true,
  featuredImageUrl: '/uploads/blog-phase3-10-aggregation.jpg',
  tags: post10Tags,
  skills: post10Skills,
  createdDate: new Date('2026-04-12T10:00:00Z'),
  updatedDate: new Date('2026-04-12T10:00:00Z'),
  _class: 'com.simonrowe.admin.Blog'
});
print('Inserted Post 10: News Aggregation (' + post10Result.insertedId + ')');

print('');
print('=== Phase 3 blog posts seeding complete ===');
print('Total: 2 blog posts, ' + newTags.length + ' new tags');
