package com.simonrowe.agents;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import com.simonrowe.aggregation.AggregatedArticle;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DigestMetadataGenerator {

  private static final Logger LOG =
      LoggerFactory.getLogger(DigestMetadataGenerator.class);

  private static final int MAX_TITLE_LENGTH = 90;
  private static final int MAX_DESCRIPTION_LENGTH = 160;

  /**
   * Matches a "Title:" label at the start of a line, tolerating leading
   * Markdown decoration (list markers, headings, code-fence backticks) and a
   * bold marker wrapped around the label itself, e.g. {@code **Title:**}.
   */
  private static final Pattern TITLE_LABEL =
      Pattern.compile("^[\\s*#`-]*Title:\\**\\s*");

  /** Same tolerance as {@link #TITLE_LABEL}, for the "Description:" label. */
  private static final Pattern DESCRIPTION_LABEL =
      Pattern.compile("^[\\s*#`-]*Description:\\**\\s*");

  private static final String METADATA_PROMPT =
      "Generate metadata for a personal editorial digest post by Simon Rowe. "
          + "Return exactly two lines in this format:\n"
          + "Title: <human title, under 90 characters>\n"
          + "Description: <one sentence, under 160 characters>\n"
          + "Use first-person curated phrasing. Do not use the phrases "
          + "'AI & Tech Roundup' or 'This week in AI'. Base the title on the specific themes "
          + "of the source material.\n\n";

  private final Ai ai;
  private final String model;

  public DigestMetadataGenerator(
      final Ai ai,
      @Value("${aggregation.digest.model}") final String model) {
    this.ai = ai;
    this.model = model;
  }

  public DigestMetadata generate(
      final List<AggregatedArticle> articles,
      final String activitySummary) {
    return generate(articles, activitySummary, model);
  }

  /**
   * Generates digest metadata using an explicitly named model rather than the
   * injected {@code aggregation.digest.model}.
   *
   * <p>Exists for {@code V006FixAiBlogTitles}: an already-executed change
   * unit must keep producing what it produced when it ran, so it pins its
   * model rather than following the digest's current config.
   *
   * @param articles the articles to fall back on if the LLM call is unusable
   * @param activitySummary the prompt content describing recent activity
   * @param modelName the model to call
   * @return the generated or fallback metadata
   */
  public DigestMetadata generate(
      final List<AggregatedArticle> articles,
      final String activitySummary,
      final String modelName) {
    try {
      String content = ai.withLlm(modelName)
          .respond(List.of(new UserMessage(METADATA_PROMPT + activitySummary)))
          .getContent();
      DigestMetadata parsed = parse(content);
      if (isUsable(parsed)) {
        return parsed;
      }
    } catch (Exception ex) {
      LOG.warn("Failed to generate digest metadata: {}", ex.getMessage());
    }
    return fallback(articles);
  }

  private static DigestMetadata parse(final String content) {
    String title = null;
    String description = null;
    if (content != null) {
      for (String line : content.split("\\R")) {
        Matcher titleMatcher = TITLE_LABEL.matcher(line);
        if (titleMatcher.find()) {
          title = truncate(
              line.substring(titleMatcher.end()).trim(), MAX_TITLE_LENGTH);
          continue;
        }
        Matcher descriptionMatcher = DESCRIPTION_LABEL.matcher(line);
        if (descriptionMatcher.find()) {
          description = truncate(
              line.substring(descriptionMatcher.end()).trim(),
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
        && !metadata.title().toLowerCase().contains("this week in ai")
        && metadata.shortDescription() != null
        && !metadata.shortDescription().isBlank();
  }

  private static DigestMetadata fallback(
      final List<AggregatedArticle> articles) {
    String lead = articles.stream()
        .findFirst()
        .map(AggregatedArticle::title)
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
