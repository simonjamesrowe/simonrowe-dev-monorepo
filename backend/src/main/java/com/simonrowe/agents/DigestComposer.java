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
      Pattern.compile("^#\\s", Pattern.MULTILINE);

  private static final Pattern FENCED_CODE_BLOCK =
      Pattern.compile("```.*?```", Pattern.DOTALL);

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

  private static final String SYNTHESIS_PROMPT = """
      Below is a draft digest post by Simon Rowe, assembled from one section \
      per article he saved this week.

      Rewrite it as a single flowing piece in his first-person voice. Add a \
      short 2-3 sentence intro at the top. Keep exactly one section per \
      article, in the same order.

      Rules you must not break:
      - Reproduce every Markdown link EXACTLY as written, including the URL.
      - Keep every "### [Title](url)" heading exactly as given, at that \
      exact heading level.
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
    String winner;
    if (synthesised == null || !preservesEveryUrl(synthesised, sections)
        || containsTopLevelHeading(synthesised)
        || containsHtml(synthesised)) {
      LOG.warn("Synthesis pass rejected for {} sections; "
          + "publishing the assembled document", sections.size());
      winner = assembled;
    } else {
      winner = synthesised;
    }
    // Belt-and-braces: strip any HTML tag that made it this far regardless of
    // which variant won, including the assembled document — a section body
    // is untrusted third-party text laundered through a model, so even a
    // guarded section is one prompt-injection variant away from emitting a
    // tag. This can also strip an HTML-looking tag from a legitimate code
    // example, but a public, auto-published, unreviewed page is the wrong
    // place to take that risk for a digest that is prose about articles,
    // not a tutorial.
    return stripHtml(winner);
  }

  private static String assemble(final List<DigestSection> sections) {
    StringBuilder sb = new StringBuilder();
    for (DigestSection section : sections) {
      // section.title() is never model-generated (it comes straight from
      // AggregatedArticle.title(), itself raw third-party feed/page text —
      // see DigestSection's Javadoc), so it never passes through
      // ArticleSectionWriter's HTML guard the way a generated body does.
      // Strip it here rather than relying solely on the final belt-and-braces
      // pass below.
      // h3 rather than h2: the heading carries a link, so it inherits the
      // full link treatment, and at h2 the result reads as a wall of large
      // underlined blue text rather than as section headings.
      sb.append("### [").append(stripHtml(section.title()))
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
    String withoutCodeBlocks =
        FENCED_CODE_BLOCK.matcher(synthesised).replaceAll("");
    return TOP_LEVEL_HEADING.matcher(withoutCodeBlocks).find();
  }

  private static boolean containsHtml(final String text) {
    return text != null && HTML_TAG.matcher(text).find();
  }

  /**
   * Strips HTML tags to a fixpoint rather than in a single pass. A single
   * pass on a nested construct such as {@code <<b>script>alert(1)<<b>/script>}
   * removes only the inner {@code <b>} tags and reassembles a live
   * {@code <script>alert(1)</script>}; repeating the strip until the result
   * stops changing collapses it down to the inert text {@code alert(1)}.
   */
  private static String stripHtml(final String text) {
    String previous;
    String stripped = text;
    do {
      previous = stripped;
      stripped = HTML_TAG.matcher(previous).replaceAll("");
    } while (!stripped.equals(previous));
    return stripped;
  }
}
