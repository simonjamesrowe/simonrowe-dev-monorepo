package com.simonrowe.embedding;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {

  private static final Logger LOG = LoggerFactory.getLogger(EmbeddingService.class);

  private final VectorStore vectorStore;
  private final TokenTextSplitter splitter;
  private final BlogRepository blogRepository;
  private final JobRepository jobRepository;
  private final SkillRepository skillRepository;
  private final SkillGroupRepository skillGroupRepository;
  private final AdminCodeExampleRepository codeExampleRepository;
  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;

  public EmbeddingService(
      final VectorStore vectorStore,
      final TokenTextSplitter splitter,
      final BlogRepository blogRepository,
      final JobRepository jobRepository,
      final SkillRepository skillRepository,
      final SkillGroupRepository skillGroupRepository,
      final AdminCodeExampleRepository codeExampleRepository,
      final AggregatedArticleRepository articleRepository,
      final AggregatedEventRepository eventRepository
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
  }

  @WithSpan
  public void embedBlog(final Blog blog) {
    removeContent(blog.id());
    if (!blog.published()) {
      LOG.info("Blog {} is not published, skipping embedding", blog.id());
      return;
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
    String content = blog.title() + "\n\n" + blog.shortDescription()
        + "\n\n" + blog.content();
    embedContent(content, metadata);
    LOG.info("Embedded blog: {} ({} chars)", blog.title(), content.length());
  }

  @WithSpan
  public void embedJob(final Job job) {
    removeContent(job.id());
    String content = job.title() + " at " + job.company() + "\n\n"
        + job.shortDescription();
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
    embedContent(content, metadata);
    LOG.info("Embedded job: {} at {}", job.title(), job.company());
  }

  @WithSpan
  public void embedSkill(final Skill skill, final SkillGroup group) {
    removeContent(skill.id());
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
    embedContent(content.toString(), metadata);
    LOG.info("Embedded skill: {}", skill.name());
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
    removeContent(example.id());
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
    embedContent(content.toString(), metadata);
    LOG.info("Embedded code example: {} ({})", example.title(), example.language());
  }

  @WithSpan
  public void embedArticle(final AggregatedArticle article) {
    removeContent("news_" + article.id());
    if (!article.visible()) {
      LOG.info("Article {} is not visible, skipping embedding", article.id());
      return;
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
    embedContent(content.toString(), metadata);
    LOG.info("Embedded article: {}", article.title());
  }

  @WithSpan
  public void embedEvent(final AggregatedEvent event) {
    removeContent("event_" + event.id());
    if (!event.visible()) {
      LOG.info("Event {} is not visible, skipping embedding", event.id());
      return;
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
    embedContent(content.toString(), metadata);
    LOG.info("Embedded event: {}", event.title());
  }

  public int embedAllArticles() {
    List<AggregatedArticle> articles = articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc();
    articles.forEach(this::embedArticle);
    LOG.info("Embedded {} articles", articles.size());
    return articles.size();
  }

  public int embedAllEvents() {
    List<AggregatedEvent> events = eventRepository.findByVisibleTrueOrderByEventDateDesc();
    events.forEach(this::embedEvent);
    LOG.info("Embedded {} events", events.size());
    return events.size();
  }

  @Scheduled(cron = "${search.sync.cron:0 0 */4 * * *}")
  public void fullVectorSync() {
    LOG.info("Starting full vector sync");
    try {
      embedAllBlogs();
      embedAllJobs();
      embedAllSkills();
      embedAllCodeExamples();
      embedAllArticles();
      embedAllEvents();
      LOG.info("Full vector sync completed");
    } catch (Exception ex) {
      LOG.error("Full vector sync failed", ex);
    }
  }

  public int embedAllBlogs() {
    List<Blog> blogs = blogRepository.findByPublishedTrueOrderByCreatedDateDesc();
    blogs.forEach(this::embedBlog);
    LOG.info("Embedded {} published blogs", blogs.size());
    return blogs.size();
  }

  public int embedAllJobs() {
    List<Job> jobs = jobRepository.findAllByOrderByStartDateDesc();
    jobs.forEach(this::embedJob);
    LOG.info("Embedded {} jobs", jobs.size());
    return jobs.size();
  }

  public int embedAllSkills() {
    int count = 0;
    for (SkillGroup group : skillGroupRepository.findAllByOrderByDisplayOrderAsc()) {
      if (group.skills() == null) {
        continue;
      }
      for (String skillId : group.skills()) {
        skillRepository.findById(skillId).ifPresent(skill -> embedSkill(skill, group));
      }
      count += group.skills().size();
    }
    LOG.info("Embedded {} skills", count);
    return count;
  }

  public int embedAllCodeExamples() {
    List<CodeExample> examples = codeExampleRepository.findAll();
    examples.forEach(this::embedCodeExample);
    LOG.info("Embedded {} code examples", examples.size());
    return examples.size();
  }

  private void embedContent(final String content, final Map<String, Object> metadata) {
    Document document = new Document(content, metadata);
    List<Document> chunks = splitter.apply(List.of(document));
    vectorStore.add(chunks);
  }
}
