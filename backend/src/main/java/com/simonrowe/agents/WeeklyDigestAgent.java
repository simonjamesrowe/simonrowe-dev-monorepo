package com.simonrowe.agents;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Skill;
import com.simonrowe.blog.Tag;
import com.simonrowe.blog.TagRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.media.BlogImageGenerationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
      "Write a weekly digest blog post in Markdown format "
          + "summarizing the following activity. Write in a "
          + "friendly, professional tone. Include sections for "
          + "blog posts and tech news. Keep it concise "
          + "(300-500 words). Do not include a title heading "
          + "(it will be added separately).\n\n";

  private static final String DIGEST_SHORT_DESCRIPTION =
      "Weekly summary of site activity and tech news";

  private final BlogRepository blogRepository;
  private final TagRepository tagRepository;
  private final AggregatedArticleRepository articleRepository;
  private final Ai ai;
  private final ContentChangePublisher changePublisher;
  private final BlogImageGenerationService blogImageGenerationService;

  public WeeklyDigestAgent(
      final BlogRepository blogRepository,
      final TagRepository tagRepository,
      final AggregatedArticleRepository articleRepository,
      final Ai ai,
      final ContentChangePublisher changePublisher,
      final BlogImageGenerationService blogImageGenerationService) {
    this.blogRepository = blogRepository;
    this.tagRepository = tagRepository;
    this.articleRepository = articleRepository;
    this.ai = ai;
    this.changePublisher = changePublisher;
    this.blogImageGenerationService = blogImageGenerationService;
  }

  @Action(description = "Generate a weekly digest blog post")
  public void generateDigest() {
    Instant oneWeekAgo =
        Instant.now().minus(7, ChronoUnit.DAYS);
    log.info(
        "Generating weekly digest for period since {}",
        oneWeekAgo);

    List<Blog> recentBlogs = blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc().stream()
        .filter(b -> b.createdDate() != null
            && b.createdDate().isAfter(oneWeekAgo))
        .toList();

    List<AggregatedArticle> recentArticles = articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc().stream()
        .filter(a -> a.fetchedAt() != null
            && a.fetchedAt().isAfter(oneWeekAgo))
        .toList();

    if (recentBlogs.isEmpty() && recentArticles.isEmpty()) {
      log.info(
          "Quiet week - no activity to summarize, "
              + "skipping digest generation");
      return;
    }

    String activitySummary =
        buildActivitySummary(recentBlogs, recentArticles);
    String digestContent =
        generateDigestContent(activitySummary);

    LocalDate now = LocalDate.now(ZoneOffset.UTC);
    LocalDate weekStart = now.minusDays(7);
    DateTimeFormatter fmt =
        DateTimeFormatter.ofPattern("MMM d");
    String title = "Week in Review: "
        + weekStart.format(fmt) + " - " + now.format(fmt)
        + ", " + now.getYear();

    String featuredImageUrl =
        blogImageGenerationService.generateAndStore(
            title, DIGEST_SHORT_DESCRIPTION);

    Tag digestTag = getOrCreateDigestTag();
    Instant createdAt = Instant.now();
    Blog digest = new Blog(
        null, title,
        DIGEST_SHORT_DESCRIPTION,
        digestContent, true, featuredImageUrl,
        createdAt, createdAt,
        List.of(digestTag), List.<Skill>of());

    Blog saved = blogRepository.save(digest);
    changePublisher.publishCreated(ContentType.BLOG, saved.id());
    log.info("Published weekly digest: {}", title);
  }

  private String buildActivitySummary(
      final List<Blog> recentBlogs,
      final List<AggregatedArticle> recentArticles) {
    StringBuilder sb = new StringBuilder();
    if (!recentBlogs.isEmpty()) {
      sb.append("## New Blog Posts This Week\n");
      for (Blog blog : recentBlogs) {
        sb.append("- ").append(blog.title())
            .append(": ").append(blog.shortDescription())
            .append("\n");
      }
      sb.append("\n");
    }
    if (!recentArticles.isEmpty()) {
      sb.append("## Notable Tech News This Week\n");
      for (AggregatedArticle a : recentArticles.stream()
          .limit(10).collect(Collectors.toList())) {
        sb.append("- ").append(a.title())
            .append(" (").append(a.sourceName()).append(")")
            .append(": ").append(a.summary()).append("\n");
      }
    }
    return sb.toString();
  }

  private String generateDigestContent(
      final String activitySummary) {
    try {
      return ai.withDefaultLlm()
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
