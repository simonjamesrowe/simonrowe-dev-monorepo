package com.simonrowe.embedding;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.simonrowe.admin.AdminCodeExampleRepository;
import com.simonrowe.admin.CodeExample;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import com.simonrowe.skills.SkillRepository;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Writes the site's content into the {@code content-embeddings} vector index.
 *
 * <p>Every item's vectors carry a {@link #CONTENT_HASH} of the text and metadata they were made
 * from, and an item whose indexed chunks all carry the current hash is not embedded again. The
 * 4-hourly {@link #fullVectorSync()} used to re-embed every blog, job, skill, code example,
 * article and event regardless: about 6,800 embedding calls and 12M tokens a day in October
 * 2026, growing with every article, for content that had not changed. The index itself is the
 * record of what is embedded, not a second ledger, so an index that lost its vectors (wiped,
 * restored from an older backup, or a failed write) is re-embedded on the next sync instead of
 * being trusted as current.
 */
@Service
public class EmbeddingService {

  private static final Logger LOG = LoggerFactory.getLogger(EmbeddingService.class);

  /** Metadata key holding the fingerprint of what a chunk was embedded from. */
  static final String CONTENT_HASH = "contentHash";

  /**
   * Part of every fingerprint. Bump it when the way content becomes vectors changes (the
   * splitter settings in {@link EmbeddingConfig}, the embedding model, or the text each
   * {@code embed*} method assembles in a way the fingerprint cannot see) so the next sync
   * re-embeds everything once.
   */
  static final String FORMAT_VERSION = "embedding-v1";

  private final VectorStore vectorStore;
  private final TokenTextSplitter splitter;
  private final BlogRepository blogRepository;
  private final JobRepository jobRepository;
  private final SkillRepository skillRepository;
  private final SkillGroupRepository skillGroupRepository;
  private final AdminCodeExampleRepository codeExampleRepository;
  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;
  private final ElasticsearchClient esClient;
  private final String indexName;

  public EmbeddingService(
      final VectorStore vectorStore,
      final TokenTextSplitter splitter,
      final BlogRepository blogRepository,
      final JobRepository jobRepository,
      final SkillRepository skillRepository,
      final SkillGroupRepository skillGroupRepository,
      final AdminCodeExampleRepository codeExampleRepository,
      final AggregatedArticleRepository articleRepository,
      final AggregatedEventRepository eventRepository,
      final ElasticsearchClient esClient,
      @Value("${spring.ai.vectorstore.elasticsearch.index-name:content-embeddings}")
      final String indexName
  ) {
    this.vectorStore = vectorStore;
    this.splitter = splitter;
    this.blogRepository = blogRepository;
    this.jobRepository = jobRepository;
    this.skillRepository = skillRepository;
    this.skillGroupRepository = skillGroupRepository;
    this.codeExampleRepository = codeExampleRepository;
    this.articleRepository = articleRepository;
    this.eventRepository = eventRepository;
    this.esClient = esClient;
    this.indexName = indexName;
  }

  @WithSpan
  public void embedBlog(final Blog blog) {
    embedBlog(blog, false);
  }

  private boolean embedBlog(final Blog blog, final boolean force) {
    if (!blog.published()) {
      removeContent(blog.id());
      LOG.info("Blog {} is not published, skipping embedding", blog.id());
      return false;
    }
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("sourceId", blog.id());
    metadata.put("sourceType", "blog");
    metadata.put("title", blog.title());
    if (blog.tags() != null) {
      metadata.put("tags", blog.tags().stream()
          .map(t -> t.name())
          .collect(Collectors.joining(",")));
    }
    if (blog.skills() != null) {
      metadata.put("skills", blog.skills().stream()
          .map(s -> s.name())
          .collect(Collectors.joining(",")));
    }
    metadata.put("url", "/blogs/" + blog.id());
    String content = """
        %s

        %s

        %s""".formatted(blog.title(), blog.shortDescription(), blog.content());
    return index(content, metadata, force);
  }

  @WithSpan
  public void embedJob(final Job job) {
    embedJob(job, false);
  }

  private boolean embedJob(final Job job, final boolean force) {
    String content = """
        %s at %s

        %s""".formatted(job.title(), job.company(), job.shortDescription());
    if (job.longDescription() != null) {
      content += "\n\n" + job.longDescription();
    }
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("sourceId", job.id());
    metadata.put("sourceType", "job");
    metadata.put("title", job.title() + " at " + job.company());
    if (job.skills() != null) {
      metadata.put("skills", String.join(",", job.skills()));
    }
    metadata.put("url", "/jobs/" + job.id());
    return index(content, metadata, force);
  }

  @WithSpan
  public void embedSkill(final Skill skill, final SkillGroup group) {
    embedSkill(skill, group, false);
  }

  private boolean embedSkill(final Skill skill, final SkillGroup group, final boolean force) {
    StringBuilder content = new StringBuilder();
    content.append(skill.name());
    if (skill.description() != null) {
      content.append("\n\n").append(skill.description());
    }
    if (group != null && group.description() != null) {
      content.append("\n\nPart of ").append(group.name())
          .append(": ").append(group.description());
    }
    if (skill.rating() != null) {
      content.append("\n\nProficiency: ").append(skill.rating()).append("/10");
    }
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("sourceId", skill.id());
    metadata.put("sourceType", "skill");
    metadata.put("title", skill.name());
    if (group != null) {
      metadata.put("skills", group.name());
    }
    metadata.put("url", "/skills-groups/" + (group != null ? group.id() : ""));
    return index(content.toString(), metadata, force);
  }

  @WithSpan
  public void removeContent(final String sourceId) {
    try {
      FilterExpressionBuilder builder = new FilterExpressionBuilder();
      vectorStore.delete(builder.eq("sourceId", sourceId).build());
      LOG.debug("Removed embeddings for sourceId: {}", sourceId);
    } catch (Exception ex) {
      LOG.debug("Could not delete embeddings for sourceId={}: {}",
          sourceId, ex.getMessage());
    }
  }

  @WithSpan
  public void embedCodeExample(final CodeExample example) {
    embedCodeExample(example, false);
  }

  private boolean embedCodeExample(final CodeExample example, final boolean force) {
    StringBuilder content = new StringBuilder();
    content.append(example.title()).append("\n\n");
    content.append(example.description()).append("\n\n");
    content.append("```").append(example.language()).append("\n");
    content.append(example.code()).append("\n```");
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("sourceId", example.id());
    metadata.put("sourceType", "code_example");
    metadata.put("title", example.title());
    metadata.put("language", example.language());
    if (example.skills() != null) {
      metadata.put("skills", example.skills().stream()
          .map(s -> s.name())
          .collect(Collectors.joining(",")));
    }
    metadata.put("url", "/code-examples/" + example.id());
    return index(content.toString(), metadata, force);
  }

  @WithSpan
  public void embedArticle(final AggregatedArticle article) {
    embedArticle(article, false);
  }

  private boolean embedArticle(final AggregatedArticle article, final boolean force) {
    if (!article.visible()) {
      removeContent("news_" + article.id());
      LOG.info("Article {} is not visible, skipping embedding", article.id());
      return false;
    }
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("sourceId", "news_" + article.id());
    metadata.put("sourceType", "aggregated_article");
    metadata.put("title", article.title());
    metadata.put("url", article.originalUrl());
    metadata.put("sourceName", article.sourceName());
    StringBuilder content = new StringBuilder();
    content.append(article.title()).append("\n\n");
    if (article.summary() != null) {
      content.append(article.summary()).append("\n\n");
    }
    if (article.fullContent() != null) {
      content.append(article.fullContent());
    }
    return index(content.toString(), metadata, force);
  }

  @WithSpan
  public void embedEvent(final AggregatedEvent event) {
    embedEvent(event, false);
  }

  private boolean embedEvent(final AggregatedEvent event, final boolean force) {
    if (!event.visible()) {
      removeContent("event_" + event.id());
      LOG.info("Event {} is not visible, skipping embedding", event.id());
      return false;
    }
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("sourceId", "event_" + event.id());
    metadata.put("sourceType", "aggregated_event");
    metadata.put("title", event.title());
    metadata.put("url", event.originalUrl());
    metadata.put("sourceName", event.sourceName());
    StringBuilder content = new StringBuilder();
    content.append(event.title()).append("\n\n");
    if (event.summary() != null) {
      content.append(event.summary()).append("\n\n");
    }
    if (event.description() != null) {
      content.append(event.description());
    }
    return index(content.toString(), metadata, force);
  }

  /**
   * Re-embeds every visible article, whether or not it changed.
   *
   * @return how many articles were embedded
   */
  public int embedAllArticles() {
    return embedAllArticles(true);
  }

  private int embedAllArticles(final boolean force) {
    List<AggregatedArticle> articles = articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc();
    return report("articles", articles, article -> embedArticle(article, force));
  }

  /**
   * Re-embeds every visible event, whether or not it changed.
   *
   * @return how many events were embedded
   */
  public int embedAllEvents() {
    return embedAllEvents(true);
  }

  private int embedAllEvents(final boolean force) {
    List<AggregatedEvent> events = eventRepository.findByVisibleTrueOrderByEventDateDesc();
    return report("events", events, event -> embedEvent(event, force));
  }

  /**
   * The scheduled safety net behind the per-item Kafka path. It embeds only what is missing from
   * the index or has changed since it was embedded; the admin Data Ops "Re-embed" button is the
   * way to force everything.
   */
  @Scheduled(cron = "${search.sync.cron:0 0 */4 * * *}")
  public void fullVectorSync() {
    LOG.info("Starting full vector sync");
    try {
      int embedded = embedAllBlogs(false)
          + embedAllJobs(false)
          + embedAllSkills(false)
          + embedAllCodeExamples(false)
          + embedAllArticles(false)
          + embedAllEvents(false);
      LOG.info("Full vector sync completed: {} items embedded", embedded);
    } catch (Exception ex) {
      LOG.error("Full vector sync failed", ex);
    }
  }

  /**
   * Re-embeds every published blog, whether or not it changed.
   *
   * @return how many blogs were embedded
   */
  public int embedAllBlogs() {
    return embedAllBlogs(true);
  }

  private int embedAllBlogs(final boolean force) {
    List<Blog> blogs = blogRepository.findByPublishedTrueOrderByCreatedDateDesc();
    return report("published blogs", blogs, blog -> embedBlog(blog, force));
  }

  /**
   * Re-embeds every job, whether or not it changed.
   *
   * @return how many jobs were embedded
   */
  public int embedAllJobs() {
    return embedAllJobs(true);
  }

  private int embedAllJobs(final boolean force) {
    List<Job> jobs = jobRepository.findAllByOrderByStartDateDesc();
    return report("jobs", jobs, job -> embedJob(job, force));
  }

  /**
   * Re-embeds every skill, whether or not it changed.
   *
   * @return how many skills were embedded
   */
  public int embedAllSkills() {
    return embedAllSkills(true);
  }

  private int embedAllSkills(final boolean force) {
    int seen = 0;
    int embedded = 0;
    for (SkillGroup group : skillGroupRepository.findAllByOrderByDisplayOrderAsc()) {
      if (group.skills() == null) {
        continue;
      }
      for (String skillId : group.skills()) {
        Optional<Skill> skill = skillRepository.findById(skillId);
        if (skill.isPresent()) {
          seen++;
          if (embedSkill(skill.get(), group, force)) {
            embedded++;
          }
        }
      }
    }
    LOG.info("Embedded {} of {} skills ({} unchanged)", embedded, seen, seen - embedded);
    return embedded;
  }

  /**
   * Re-embeds every code example, whether or not it changed.
   *
   * @return how many code examples were embedded
   */
  public int embedAllCodeExamples() {
    return embedAllCodeExamples(true);
  }

  private int embedAllCodeExamples(final boolean force) {
    List<CodeExample> examples = codeExampleRepository.findAll();
    return report("code examples", examples, example -> embedCodeExample(example, force));
  }

  private static <T> int report(
      final String kind, final List<T> items, final Predicate<T> embed) {
    int embedded = 0;
    for (T item : items) {
      if (embed.test(item)) {
        embedded++;
      }
    }
    LOG.info("Embedded {} of {} {} ({} unchanged)",
        embedded, items.size(), kind, items.size() - embedded);
    return embedded;
  }

  /**
   * Embeds one item's text unless the index already holds it unchanged.
   *
   * <p>The old chunks are deleted only once the item is known to need re-embedding, so an
   * unchanged item never disappears from search, even for the moment a re-embed takes.
   *
   * @param content the text to embed
   * @param metadata the item's metadata; must carry {@code sourceId}
   * @param force embed even when the indexed chunks match
   * @return whether an embedding call was made
   */
  private boolean index(
      final String content, final Map<String, Object> metadata, final boolean force) {
    final String sourceId = (String) metadata.get("sourceId");
    final String hash = fingerprint(content, metadata);
    if (!force && indexedUnchanged(sourceId, hash)) {
      LOG.debug("Embeddings for {} are current, not re-embedding", sourceId);
      return false;
    }
    removeContent(sourceId);
    metadata.put(CONTENT_HASH, hash);
    Document document = new Document(content, metadata);
    List<Document> chunks = splitter.apply(List.of(document));
    vectorStore.add(chunks);
    LOG.info("Embedded {} ({} chars, {} chunks)", sourceId, content.length(), chunks.size());
    return true;
  }

  /**
   * Whether every chunk indexed under {@code sourceId} was embedded from exactly this content.
   *
   * <p>Answered with two {@code _count} requests, which cost no embedding call. Any failure
   * (no index yet, Elasticsearch unreachable, a mapping without the {@code .keyword} fields)
   * answers {@code false}, so the item is re-embedded: the cost of a wrong "unchanged" is a
   * stale or missing search result, the cost of a wrong "changed" is one embedding call.
   *
   * @param sourceId the item's sourceId
   * @param hash the fingerprint of what would be embedded now
   * @return true only when at least one chunk exists and all of them carry {@code hash}
   */
  boolean indexedUnchanged(final String sourceId, final String hash) {
    try {
      final Query bySource = term("metadata.sourceId.keyword", sourceId);
      final long indexed = esClient.count(c -> c.index(indexName).query(bySource)).count();
      if (indexed == 0) {
        return false;
      }
      final Query byHash = term("metadata." + CONTENT_HASH + ".keyword", hash);
      final long current = esClient.count(c -> c.index(indexName)
          .query(q -> q.bool(b -> b.filter(bySource).filter(byHash)))).count();
      return current == indexed;
    } catch (Exception ex) {
      LOG.debug("Could not check embeddings for {}: {}", sourceId, ex.getMessage());
      return false;
    }
  }

  private static Query term(final String field, final String value) {
    return Query.of(q -> q.term(t -> t.field(field).value(value)));
  }

  /**
   * SHA-256 over the format version, the text and every metadata entry in key order, so a
   * change to a title or URL re-embeds as surely as a change to the body.
   *
   * @param content the text to embed
   * @param metadata the item's metadata, without {@link #CONTENT_HASH}
   * @return the lowercase hex digest
   */
  static String fingerprint(final String content, final Map<String, Object> metadata) {
    final Map<String, Object> sorted = new TreeMap<>(metadata);
    sorted.remove(CONTENT_HASH);
    final StringBuilder input = new StringBuilder(FORMAT_VERSION).append('\u0000')
        .append(content);
    sorted.forEach((key, value) ->
        input.append('\u0000').append(key).append('=').append(value));
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest(input.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }
}
