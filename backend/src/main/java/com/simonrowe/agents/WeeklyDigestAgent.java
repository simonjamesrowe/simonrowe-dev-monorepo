package com.simonrowe.agents;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Skill;
import com.simonrowe.blog.Tag;
import com.simonrowe.blog.TagRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.media.BlogImageGenerationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Agent(
    name = "WeeklyDigest",
    description = "Generates a weekly digest blog post summarising "
        + "recent site activity and aggregated tech news"
)
public class WeeklyDigestAgent {

  private static final Logger log =
      LoggerFactory.getLogger(WeeklyDigestAgent.class);

  private static final String DIGEST_PROMPT =
      "Write a digest blog post in Markdown format summarizing "
          + "the following activity. Write in a friendly, "
          + "professional tone as Simon Rowe (first person). "
          + "For each item, include a Markdown link using the "
          + "URL provided. Keep it concise (300-500 words). "
          + "Do not include a title heading (it will be added "
          + "separately). Group by sections (blog posts, tech "
          + "news) if both are present.\n\n";

  private final BlogRepository blogRepository;
  private final TagRepository tagRepository;
  private final AggregatedArticleRepository articleRepository;
  private final Ai ai;
  private final ContentChangePublisher changePublisher;
  private final BlogImageGenerationService blogImageGenerationService;
  private final DigestMetadataGenerator metadataGenerator;

  public WeeklyDigestAgent(
      final BlogRepository blogRepository,
      final TagRepository tagRepository,
      final AggregatedArticleRepository articleRepository,
      final Ai ai,
      final ContentChangePublisher changePublisher,
      final BlogImageGenerationService blogImageGenerationService,
      final DigestMetadataGenerator metadataGenerator) {
    this.blogRepository = blogRepository;
    this.tagRepository = tagRepository;
    this.articleRepository = articleRepository;
    this.ai = ai;
    this.changePublisher = changePublisher;
    this.blogImageGenerationService = blogImageGenerationService;
    this.metadataGenerator = metadataGenerator;
  }

  @Action(description = "Generate a digest blog post")
  public void generateDigest() {
    Tag digestTag = getOrCreateDigestTag();
    Instant sinceDate = findLastDigestDate(digestTag);
    log.info("Generating digest for period since {}", sinceDate);

    List<Blog> recentBlogs = blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc().stream()
        .filter(b -> b.createdDate() != null
            && b.createdDate().isAfter(sinceDate))
        .filter(b -> !isDigestBlog(b, digestTag))
        .toList();

    List<AggregatedArticle> recentArticles = articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc().stream()
        .filter(a -> a.fetchedAt() != null
            && a.fetchedAt().isAfter(sinceDate))
        .toList();

    if (recentBlogs.isEmpty() && recentArticles.isEmpty()) {
      log.info(
          "Nothing new since last digest, "
              + "skipping generation");
      return;
    }

    String activitySummary =
        buildActivitySummary(recentBlogs, recentArticles);
    String digestContent =
        generateDigestContent(activitySummary);
    DigestMetadata metadata = metadataGenerator.generate(
        recentBlogs, recentArticles, activitySummary);
    String imageContext = buildImageContext(recentBlogs, recentArticles);

    String featuredImageUrl =
        blogImageGenerationService.generateAndStore(
            metadata.title(), metadata.shortDescription(), imageContext);

    Instant createdAt = Instant.now();
    Blog digest = new Blog(
        null, metadata.title(),
        metadata.shortDescription(),
        digestContent, true, featuredImageUrl,
        createdAt, createdAt,
        List.of(digestTag), List.<Skill>of(),
        BlogContentType.DIGEST);

    Blog saved = blogRepository.save(digest);
    changePublisher.publishCreated(ContentType.BLOG, saved.id());
    log.info("Published digest: {}", metadata.title());
  }

  private Instant findLastDigestDate(final Tag digestTag) {
    return blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc().stream()
        .filter(b -> isDigestBlog(b, digestTag))
        .map(Blog::createdDate)
        .findFirst()
        .orElse(Instant.now().minus(7, ChronoUnit.DAYS));
  }

  private boolean isDigestBlog(final Blog blog, final Tag digestTag) {
    return blog.tags() != null && blog.tags().stream()
        .anyMatch(t -> t.id().equals(digestTag.id()));
  }

  private String buildActivitySummary(
      final List<Blog> recentBlogs,
      final List<AggregatedArticle> recentArticles) {
    StringBuilder sb = new StringBuilder();
    if (!recentBlogs.isEmpty()) {
      sb.append("## New Blog Posts\n");
      for (Blog blog : recentBlogs) {
        sb.append("- [").append(blog.title())
            .append("](/blogs/").append(blog.id()).append(")")
            .append(": ").append(blog.shortDescription())
            .append("\n");
      }
      sb.append("\n");
    }
    if (!recentArticles.isEmpty()) {
      sb.append("## Notable Tech News\n");
      for (AggregatedArticle a : recentArticles.stream()
          .limit(15).collect(Collectors.toList())) {
        sb.append("- [").append(a.title())
            .append("](").append(a.originalUrl()).append(")")
            .append(" (").append(a.sourceName()).append(")")
            .append(": ").append(a.summary()).append("\n");
      }
    }
    return sb.toString();
  }

  private String buildImageContext(
      final List<Blog> recentBlogs,
      final List<AggregatedArticle> recentArticles) {
    StringBuilder sb = new StringBuilder();
    if (!recentBlogs.isEmpty()) {
      sb.append("Recent Simon posts: ");
      recentBlogs.stream().limit(5)
          .forEach(blog -> sb.append(blog.title())
              .append(" - ")
              .append(blog.shortDescription())
              .append("; "));
    }
    if (!recentArticles.isEmpty()) {
      sb.append("External sources: ");
      recentArticles.stream().limit(8)
          .forEach(article -> sb.append(article.title())
              .append(" from ")
              .append(article.sourceName())
              .append(" - ")
              .append(article.summary())
              .append("; "));
    }
    return sb.toString();
  }

  private String generateDigestContent(
      final String activitySummary) {
    try {
      return ai.withLlm("gpt-4o-mini")
          .respond(List.of(
              new UserMessage(DIGEST_PROMPT + activitySummary)))
          .getContent();
    } catch (Exception e) {
      log.error(
          "Failed to generate digest via LLM, using raw summary",
          e);
      return activitySummary;
    }
  }

  private Tag getOrCreateDigestTag() {
    return tagRepository.findByName("Weekly Digest")
        .orElseGet(() -> tagRepository.save(
            new Tag(null, "Weekly Digest")));
  }
}
