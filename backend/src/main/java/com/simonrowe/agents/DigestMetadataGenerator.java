package com.simonrowe.agents;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.blog.Blog;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DigestMetadataGenerator {

  private static final Logger LOG =
      LoggerFactory.getLogger(DigestMetadataGenerator.class);

  private static final int MAX_TITLE_LENGTH = 90;
  private static final int MAX_DESCRIPTION_LENGTH = 160;

  private static final String METADATA_PROMPT =
      "Generate metadata for a personal editorial digest post by Simon Rowe. "
          + "Return exactly two lines in this format:\n"
          + "Title: <human title, under 90 characters>\n"
          + "Description: <one sentence, under 160 characters>\n"
          + "Use first-person curated phrasing. Do not use the phrase "
          + "'AI & Tech Roundup'. Base the title on the source material.\n\n";

  private final Ai ai;

  public DigestMetadataGenerator(final Ai ai) {
    this.ai = ai;
  }

  public DigestMetadata generate(
      final List<Blog> recentBlogs,
      final List<AggregatedArticle> recentArticles,
      final String activitySummary) {
    try {
      String content = ai.withLlm("gpt-4o-mini")
          .respond(List.of(new UserMessage(METADATA_PROMPT + activitySummary)))
          .getContent();
      DigestMetadata parsed = parse(content);
      if (isUsable(parsed)) {
        return parsed;
      }
    } catch (Exception ex) {
      LOG.warn("Failed to generate digest metadata: {}", ex.getMessage());
    }
    return fallback(recentBlogs, recentArticles);
  }

  private static DigestMetadata parse(final String content) {
    String title = null;
    String description = null;
    if (content != null) {
      for (String line : content.split("\\R")) {
        if (line.startsWith("Title:")) {
          title = truncate(line.substring("Title:".length()).trim(), MAX_TITLE_LENGTH);
        } else if (line.startsWith("Description:")) {
          description = truncate(
              line.substring("Description:".length()).trim(),
              MAX_DESCRIPTION_LENGTH);
        }
      }
    }
    return new DigestMetadata(title, description);
  }

  private static boolean isUsable(final DigestMetadata metadata) {
    return metadata.title() != null
        && !metadata.title().isBlank()
        && !metadata.title().startsWith("AI & Tech Roundup")
        && metadata.shortDescription() != null
        && !metadata.shortDescription().isBlank();
  }

  private static DigestMetadata fallback(
      final List<Blog> recentBlogs,
      final List<AggregatedArticle> recentArticles) {
    String lead = recentArticles.stream()
        .findFirst()
        .map(AggregatedArticle::title)
        .or(() -> recentBlogs.stream().findFirst().map(Blog::title))
        .orElse("AI and backend engineering");
    String title = truncate("What caught my eye: " + lead, MAX_TITLE_LENGTH);
    String description = truncate(
        "A few practical notes on " + lead + " and related engineering signals.",
        MAX_DESCRIPTION_LENGTH);
    return new DigestMetadata(title, description);
  }

  private static String truncate(final String value, final int maxLength) {
    if (value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength - 3).trim() + "...";
  }
}
