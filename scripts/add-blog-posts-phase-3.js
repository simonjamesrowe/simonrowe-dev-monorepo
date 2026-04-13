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
const newTags = ['RAG (Retrieval-Augmented Generation)', 'Web Scraping', 'Content Aggregation', 'Agents'];

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

const BLOG_10_CONTENT = `A portfolio site that only shows your own content feels static. What if it also curated relevant tech news and upcoming events? That's the idea behind the content aggregation pipeline I built — a system that scrapes external sources, uses an LLM to classify and summarise each piece, and stores everything locally.

## The Data Model

Everything starts with a \`ContentSource\` — a record that defines where to scrape and how:

\`\`\`java
@Document(collection = "content_sources")
public record ContentSource(
    @Id String id,
    @Indexed(unique = true) String name,
    String baseUrl,
    String feedUrl,
    String sitemapUrl,
    SourceType sourceType,
    ScrapeStrategy scrapeStrategy,
    @Indexed boolean active,
    Instant lastFetchedAt,
    String lastError
) {

  public enum SourceType {
    BLOG, NEWS, EVENTS
  }

  public enum ScrapeStrategy {
    RSS, SITEMAP_HTML, HTML, LUMA
  }
}
\`\`\`

The \`SourceType\` tells us what kind of content to expect. The \`ScrapeStrategy\` tells us how to fetch it. These are managed through an admin UI, so adding a new source is just filling in a form — no code changes needed.

## The Scraper Architecture

A \`ScraperFactory\` routes each source to the right scraper implementation using a Java 21 switch expression:

\`\`\`java
@Component
public class ScraperFactory {

  private final RssScraper rssScraper;
  private final SitemapHtmlScraper sitemapHtmlScraper;
  private final LumaApiScraper lumaApiScraper;

  public List<ScrapedContent> scrape(ContentSource source) {
    boolean isEvent = source.sourceType() == ContentSource.SourceType.EVENTS;
    return switch (source.scrapeStrategy()) {
      case RSS -> rssScraper.scrape(source.feedUrl(), isEvent);
      case SITEMAP_HTML -> sitemapHtmlScraper.scrape(source.sitemapUrl());
      case HTML -> sitemapHtmlScraper.scrapeEventsPage(source.baseUrl());
      case LUMA -> lumaApiScraper.scrape(source.feedUrl());
    };
  }
}
\`\`\`

Each scraper returns a list of \`ScrapedContent\` records — a uniform representation regardless of the source format:

- **RssScraper**: Parses RSS/Atom feeds using the Rome library, extracting titles, descriptions, authors, and publication dates
- **SitemapHtmlScraper**: Fetches a sitemap XML, then scrapes each page with JSoup, pulling content from article tags and Open Graph metadata
- **LumaApiScraper**: Hits the [Luma](https://lu.ma) API for event listings, extracting event dates, venues, and locations

## LLM-Powered Classification

Here's where it gets interesting. Scraped content arrives as raw text — but is it a news article or an event? What's a good summary? If it's an event, when and where is it?

Rather than writing brittle parsing rules, I ask an LLM:

\`\`\`java
ContentClassification classifyAndSummarize(final ScrapedContent content) {
  if (content.content() == null || content.content().length() < 50) {
    return new ContentClassification(
        content.isEvent() ? "event" : "article", content.title(),
        null, null, null, null);
  }
  try {
    String truncated = content.content().length() > 3000
        ? content.content().substring(0, 3000) : content.content();
    BeanOutputConverter<ContentClassification> converter =
        new BeanOutputConverter<>(ContentClassification.class);
    ChatClient client = chatClientBuilder.build();
    String response = client.prompt()
        .user(String.format(CLASSIFY_PROMPT,
            content.title(), truncated, converter.getFormat()))
        .call()
        .content();
    ContentClassification result = converter.convert(response);
    if (result != null) {
      return result;
    }
  } catch (Exception e) {
    log.warn("Classification failed for: {}. Using defaults.", content.title(), e);
  }
  return new ContentClassification(
      content.isEvent() ? "event" : "article", content.title(),
      null, null, null, null);
}
\`\`\`

Spring AI's \`BeanOutputConverter\` is the key here. It generates a JSON schema from the \`ContentClassification\` record and appends it to the prompt, telling the LLM exactly what structure to return:

\`\`\`java
public record ContentClassification(
    @JsonProperty("type") String type,
    @JsonProperty("summary") String summary,
    @JsonProperty("eventDate") String eventDate,
    @JsonProperty("venue") String venue,
    @JsonProperty("location") String location,
    @JsonProperty("publishedDate") String publishedDate
) {
  public boolean isEvent() {
    return "event".equalsIgnoreCase(type);
  }
}
\`\`\`

The prompt itself asks the LLM to classify the content type, write a 2-3 sentence summary, extract event details if applicable, and find the published date. If classification fails (network error, malformed response), we fall back to sensible defaults rather than crashing.

## The Aggregation Loop

The \`ContentAggregationAgent\` orchestrates the full pipeline:

\`\`\`java
public void runAggregation() {
  List<ContentSource> sources = sourceRepository.findByActiveTrue();
  log.info("Starting content aggregation for {} active sources", sources.size());

  for (ContentSource source : sources) {
    try {
      processSource(source);
      sourceRepository.save(new ContentSource(
          source.id(), source.name(), source.baseUrl(),
          source.feedUrl(), source.sitemapUrl(), source.sourceType(),
          source.scrapeStrategy(), source.active(),
          Instant.now(), null));
    } catch (Exception e) {
      log.error("Failed to process source: {}", source.name(), e);
      sourceRepository.save(new ContentSource(
          source.id(), source.name(), source.baseUrl(),
          source.feedUrl(), source.sitemapUrl(), source.sourceType(),
          source.scrapeStrategy(), source.active(),
          source.lastFetchedAt(), e.getMessage()));
    }
  }
}
\`\`\`

For each source, it scrapes content, skips anything already stored (deduplication by URL), classifies new items via the LLM, downloads external images locally, and saves the result. The source record is updated with the fetch timestamp on success or the error message on failure — giving the admin UI visibility into what's working and what's broken.

The deduplication check is simple but essential:

\`\`\`java
boolean alreadyExists = articleRepository.existsByOriginalUrl(content.url())
    || eventRepository.existsByOriginalUrl(content.url());
if (alreadyExists) {
  continue;
}
\`\`\`

Without it, every scheduled run would create duplicates of everything it had seen before.

## Scheduled Execution

The aggregation runs on a cron schedule via Spring's \`@Scheduled\`:

\`\`\`java
@Component
@EnableScheduling
public class AggregationScheduler {

  private final ContentAggregationAgent aggregationAgent;
  private final WeeklyDigestAgent digestAgent;

  @Scheduled(cron = "\${aggregation.schedule.cron:0 0 */6 * * *}")
  public void runScheduledAggregation() {
    log.info("Scheduled content aggregation starting");
    aggregationAgent.runAggregation();
  }

  @Scheduled(cron = "\${aggregation.digest.cron:0 0 8 * * MON}")
  public void runScheduledDigest() {
    log.info("Scheduled weekly digest generation starting");
    digestAgent.generateDigest();
  }
}
\`\`\`

Content aggregation runs every 6 hours. A separate \`WeeklyDigestAgent\` runs every Monday at 8am, using the LLM to generate a summary blog post of the week's aggregated content. Both cron expressions are configurable via application properties, so adjusting the schedule doesn't require a code change.

## Image Handling

External images are a liability — they can disappear, change, or slow down page loads. The \`ExternalImageDownloader\` fetches images from source URLs and stores them locally using the same upload infrastructure as the media library:

This means aggregated content gets the same image variant treatment as uploaded media — thumbnails for list views, large variants for detail pages — and the site never hot-links external resources.

## What I Learned

The pattern of **scrape → classify → store** is surprisingly reusable. The scrapers handle the messy reality of different content formats. The LLM handles the classification and summarisation that would be fragile to do with rules. And the storage layer (MongoDB + local images) means the site owns its data rather than depending on external availability.

The key design decision was making content sources configurable through the admin UI rather than hardcoded. Adding a new RSS feed or event calendar is a data change, not a code change. The scraper architecture just needs to support the strategy — and with four strategies (RSS, Sitemap/HTML, HTML, Luma), most tech content sources are covered.`;

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
  tagIds['Spring Boot']
]);
const post10Skills = buildDbRefArray('skills', [
  skillIds['Java 21'], skillIds['Spring Boot'],
  skillIds['React'], skillIds['Typescript']
]);

const post10Result = db.blogs.insertOne({
  title: 'Automated News Aggregation with AI-Powered Classification',
  shortDescription: 'Building a content aggregation pipeline that scrapes RSS feeds, sitemaps, and event APIs, then uses an LLM to classify and summarise each piece of content before storing it locally with downloaded images.',
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
