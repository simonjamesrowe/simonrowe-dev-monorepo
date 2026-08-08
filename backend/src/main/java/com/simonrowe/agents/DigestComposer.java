package com.simonrowe.agents;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a list of per-article sections into the finished digest body.
 *
 * <p>The deterministic document is built first and always exists. A synthesis
 * call then tries to rewrite it into one flowing piece, and its output is used
 * only if every source URL survived verbatim — rewriting a whole document is
 * where a model silently mangles links, and this makes that failure mode
 * non-publishing rather than reader-visible.
 */
@Component
public class DigestComposer {

  private static final Logger LOG =
      LoggerFactory.getLogger(DigestComposer.class);

  private static final Pattern TOP_LEVEL_HEADING =
      Pattern.compile("^#(?!#)", Pattern.MULTILINE);

  private static final String SYNTHESIS_PROMPT = """
      Below is a draft digest post by Simon Rowe, assembled from one section \
      per article he saved this week.

      Rewrite it as a single flowing piece in his first-person voice. Add a \
      short 2-3 sentence intro at the top. Keep exactly one section per \
      article, in the same order.

      Rules you must not break:
      - Reproduce every Markdown link EXACTLY as written, including the URL.
      - Keep every "## [Title](url)" heading exactly as given.
      - Do not add a top-level title heading.
      - Do not invent articles, links or facts.

      Draft:
      %s
      """;

  private final Ai ai;
  private final String model;

  public DigestComposer(
      final Ai ai,
      @Value("${aggregation.digest.model}") final String model) {
    this.ai = ai;
    this.model = model;
  }

  /**
   * Composes the digest body.
   *
   * @param sections one section per favourited article, in publication order
   * @return Markdown for the post body, with no top-level title heading
   */
  public String compose(final List<DigestSection> sections) {
    String assembled = assemble(sections);
    String synthesised = synthesise(assembled);
    if (synthesised == null || !preservesEveryUrl(synthesised, sections)
        || containsTopLevelHeading(synthesised)) {
      LOG.warn("Synthesis pass rejected for {} sections; "
          + "publishing the assembled document", sections.size());
      return assembled;
    }
    return synthesised;
  }

  private static String assemble(final List<DigestSection> sections) {
    StringBuilder sb = new StringBuilder();
    for (DigestSection section : sections) {
      sb.append("## [").append(section.title())
          .append("](").append(section.url()).append(")\n\n")
          .append(section.body()).append("\n\n");
    }
    return sb.toString().trim();
  }

  private String synthesise(final String assembled) {
    try {
      String content = ai.withLlm(model)
          .respond(List.of(
              new UserMessage(String.format(SYNTHESIS_PROMPT, assembled))))
          .getContent();
      return content == null || content.isBlank() ? null : content;
    } catch (Exception e) {
      LOG.warn("Digest synthesis call failed: {}", e.getMessage());
      return null;
    }
  }

  private static boolean preservesEveryUrl(
      final String synthesised, final List<DigestSection> sections) {
    return sections.stream()
        .allMatch(section -> synthesised.contains(section.url()));
  }

  private static boolean containsTopLevelHeading(final String synthesised) {
    return TOP_LEVEL_HEADING.matcher(synthesised).find();
  }
}
