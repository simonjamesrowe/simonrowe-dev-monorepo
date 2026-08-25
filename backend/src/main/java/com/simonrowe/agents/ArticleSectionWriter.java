package com.simonrowe.agents;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.ArticleSourceTextProvider;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns one favourited article into one digest section: asks
 * {@link ArticleSourceTextProvider} for the freshest and fullest text, then asks the model
 * for a few paragraphs about it.
 */
@Component
public class ArticleSectionWriter {

  private static final Logger LOG =
      LoggerFactory.getLogger(ArticleSectionWriter.class);

  /**
   * Matches an HTML/XML-like tag (e.g. {@code <script>}, {@code <img src=x>}).
   * A real tag never has whitespace directly after {@code <} or {@code </},
   * so "a < b" and "5<10" don't match. {@code [^<>]*} (rather than
   * {@code [^>]*}) stops a match from spanning a second {@code <}, which
   * both prevents the match from swallowing unrelated later text whenever a
   * stray {@code >} appears further on (e.g. "latency < threshold and
   * throughput > baseline") and stops a nested construct such as
   * {@code <<b>script>...} from having its outer {@code <} and inner {@code >}
   * treated as one tag.
   */
  private static final Pattern HTML_TAG =
      Pattern.compile("</?[a-zA-Z][^<>]*>");

  private static final String SECTION_PROMPT = """
      You are Simon Rowe, writing one section of your weekly digest about an \
      article you saved this week.

      Write 2-3 paragraphs summarising what this piece actually says — the \
      substance, not a description of the article. Then finish with one short \
      sentence, on its own line, beginning "Why this caught my eye:" giving \
      the angle that makes it worth someone's time.

      Write in first person, in Markdown. Do NOT write any heading — the \
      heading and the link are added separately. Do NOT repeat the title.

      Title: %s
      Source: %s

      Article text:
      %s
      """;

  private final ArticleSourceTextProvider sourceTextProvider;
  private final Ai ai;
  private final String model;

  public ArticleSectionWriter(
      final ArticleSourceTextProvider sourceTextProvider,
      final Ai ai,
      @Value("${aggregation.digest.model}") final String model) {
    this.sourceTextProvider = sourceTextProvider;
    this.ai = ai;
    this.model = model;
  }

  /**
   * Builds the digest section for a single article.
   *
   * @param article the favourited article
   * @return the section; never null, with {@code fallback} set when the model
   *     call failed and the stored summary was used instead
   */
  public DigestSection write(final AggregatedArticle article) {
    String sourceText = sourceTextProvider.sourceTextFor(article);
    if (!ArticleSourceTextProvider.clearsHardFloor(sourceText)) {
      LOG.warn("No usable source text for '{}' — fresh scrape, stored "
          + "content and stored summary are all under {} characters; "
          + "publishing the stored summary without calling the model",
          article.title(), ArticleSourceTextProvider.HARD_MIN_SOURCE_CHARS);
      return fallbackSection(article);
    }
    try {
      String prompt = String.format(
          SECTION_PROMPT, article.title(), article.sourceName(), sourceText);
      String body = ai.withLlm(model)
          .respond(List.of(new UserMessage(prompt)))
          .getContent();
      if (body == null || body.isBlank()) {
        LOG.warn("Empty completion summarising '{}', using stored summary",
            article.title());
        return fallbackSection(article);
      }
      if (containsHtml(body)) {
        LOG.warn("Model output for '{}' contained an HTML tag, "
            + "using stored summary instead", article.title());
        return fallbackSection(article);
      }
      return new DigestSection(
          article.id(), article.title(), article.originalUrl(), body, false);
    } catch (Exception e) {
      LOG.warn("Failed to summarise '{}', using stored summary: {}",
          article.title(), e.getMessage());
      return fallbackSection(article);
    }
  }

  private static DigestSection fallbackSection(final AggregatedArticle article) {
    return new DigestSection(
        article.id(), article.title(), article.originalUrl(),
        article.summary(), true);
  }

  private static boolean containsHtml(final String text) {
    return HTML_TAG.matcher(text).find();
  }
}
