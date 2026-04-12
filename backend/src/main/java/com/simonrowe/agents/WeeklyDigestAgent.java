package com.simonrowe.agents;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Skill;
import com.simonrowe.blog.Tag;
import com.simonrowe.blog.TagRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class WeeklyDigestAgent {

  private static final Logger log =
      LoggerFactory.getLogger(WeeklyDigestAgent.class);

  private final BlogRepository blogRepository;
  private final TagRepository tagRepository;
  private final AggregatedArticleRepository articleRepository;
  private final ChatClient.Builder chatClientBuilder;
  private final ContentChangePublisher changePublisher;

  public WeeklyDigestAgent(
      BlogRepository blogRepository,
      TagRepository tagRepository,
      AggregatedArticleRepository articleRepository,
      ChatClient.Builder chatClientBuilder,
      ContentChangePublisher changePublisher) {
    this.blogRepository = blogRepository;
    this.tagRepository = tagRepository;
    this.articleRepository = articleRepository;
    this.chatClientBuilder = chatClientBuilder;
    this.changePublisher = changePublisher;
  }

  public void generateDigest() {
    Instant oneWeekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
    log.info("Generating weekly digest for period since {}", oneWeekAgo);

    List<Blog> recentBlogs = blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc().stream()
        .filter(b -> b.createdDate() != null && b.createdDate().isAfter(oneWeekAgo))
        .toList();

    List<AggregatedArticle> recentArticles = articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc().stream()
        .filter(a -> a.fetchedAt() != null && a.fetchedAt().isAfter(oneWeekAgo))
        .toList();

    if (recentBlogs.isEmpty() && recentArticles.isEmpty()) {
      log.info("Quiet week - no activity to summarize, skipping digest generation");
      return;
    }

    StringBuilder activitySummary = new StringBuilder();
    if (!recentBlogs.isEmpty()) {
      activitySummary.append("## New Blog Posts This Week\n");
      for (Blog blog : recentBlogs) {
        activitySummary.append("- ").append(blog.title())
            .append(": ").append(blog.shortDescription()).append("\n");
      }
      activitySummary.append("\n");
    }
    if (!recentArticles.isEmpty()) {
      activitySummary.append("## Notable Tech News This Week\n");
      for (AggregatedArticle article : recentArticles.stream().limit(10)
          .collect(Collectors.toList())) {
        activitySummary.append("- ").append(article.title())
            .append(" (").append(article.sourceName()).append(")")
            .append(": ").append(article.summary()).append("\n");
      }
    }

    String digestContent = generateDigestContent(activitySummary.toString());

    LocalDate now = LocalDate.now(ZoneOffset.UTC);
    LocalDate weekStart = now.minusDays(7);
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM d");
    String title = "Week in Review: " + weekStart.format(fmt)
        + " - " + now.format(fmt) + ", " + now.getYear();

    Tag digestTag = getOrCreateDigestTag();
    Instant createdAt = Instant.now();
    Blog digest = new Blog(
        null, title, "Weekly summary of site activity and tech news",
        digestContent, true, null,
        createdAt, createdAt,
        List.of(digestTag), List.<Skill>of());

    Blog saved = blogRepository.save(digest);
    changePublisher.publishCreated(ContentType.BLOG, saved.id());
    log.info("Published weekly digest: {}", title);
  }

  private String generateDigestContent(String activitySummary) {
    try {
      ChatClient client = chatClientBuilder.build();
      return client.prompt()
          .user("Write a weekly digest blog post in Markdown format summarizing "
              + "the following activity. Write in a friendly, professional tone. "
              + "Include sections for blog posts and tech news. Keep it concise "
              + "(300-500 words). Do not include a title heading (it will be added "
              + "separately).\n\n" + activitySummary)
          .call()
          .content();
    } catch (Exception e) {
      log.error("Failed to generate digest via LLM, using raw summary", e);
      return activitySummary;
    }
  }

  private Tag getOrCreateDigestTag() {
    return tagRepository.findByName("Weekly Digest")
        .orElseGet(() -> tagRepository.save(new Tag(null, "Weekly Digest")));
  }
}
