package com.simonrowe.snippet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.node.Block;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

/**
 * The text rules snippets depend on: what a summary reads as once rendered, whether a quote
 * really came from it, and what a tag looks like.
 */
final class SnippetText {

  /** The longest quote that can be kept. Longer is refused, never silently cut. */
  static final int MAX_EXACT_LENGTH = 2_000;
  /** How much surrounding text the browser sends to find a passage again. */
  static final int CONTEXT_LENGTH = 32;
  /** Room for the browser's context, which can run a little over after trimming. */
  static final int MAX_CONTEXT_LENGTH = 64;
  static final int MAX_NOTE_LENGTH = 2_000;
  static final int MAX_TAGS = 20;
  static final int MAX_TAG_LENGTH = 40;

  // Possessive: a quote can be 2,000 characters of whitespace-separated words, and an
  // ordinary greedy class here gains nothing but the option to backtrack.
  private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]++");
  private static final Pattern LEADING_HASHES = Pattern.compile("^#++");
  private static final Parser PARSER = Parser.builder().build();

  private SnippetText() {
  }

  /**
   * The summary as a reader sees it, from its markdown: the words of every block, with the
   * markup gone. The browser builds its quote from the rendered page, so this is what a
   * quote is checked against.
   *
   * <p>Raw HTML contributes nothing, because the summary renderer has no raw-HTML plugin and
   * drops it too.
   *
   * @param markdown the summary body
   * @return its text, whitespace collapsed
   */
  static String renderedText(final String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    collect(PARSER.parse(markdown), text);
    return normalise(text.toString());
  }

  private static void collect(final Node node, final StringBuilder text) {
    switch (node) {
      case Text literal -> text.append(literal.getLiteral());
      case Code code -> text.append(code.getLiteral());
      case FencedCodeBlock fenced -> text.append(' ').append(fenced.getLiteral()).append(' ');
      case IndentedCodeBlock indented ->
          text.append(' ').append(indented.getLiteral()).append(' ');
      case SoftLineBreak ignored -> text.append(' ');
      case HardLineBreak ignored -> text.append(' ');
      default -> {
        // A block boundary is a paragraph break in the browser's quote, so it must not
        // glue the last word of one block to the first of the next.
        boolean block = node instanceof Block;
        if (block) {
          text.append(' ');
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
          collect(child, text);
        }
        if (block) {
          text.append(' ');
        }
      }
    }
  }

  /**
   * Collapses every run of whitespace, non-breaking spaces and paragraph breaks included, to
   * one space. The browser's quote and the server's rendering differ in nothing else.
   *
   * @param value the text
   * @return the normalised text, never null
   */
  static String normalise(final String value) {
    if (value == null) {
      return "";
    }
    return WHITESPACE.matcher(value).replaceAll(" ").trim();
  }

  /**
   * Whether a quote occurs in a summary, ignoring whitespace differences.
   *
   * @param summaryMarkdown the summary body
   * @param exact the quote
   * @return true when the summary contains it
   */
  static boolean summaryContains(final String summaryMarkdown, final String exact) {
    String quote = normalise(exact);
    return !quote.isEmpty() && renderedText(summaryMarkdown).contains(quote);
  }

  /**
   * One tag in its stored form: lower case, no leading {@code #}, spaces turned into hyphens.
   *
   * @param raw the tag as typed
   * @return the tag, or an empty string when nothing is left
   */
  static String normaliseTag(final String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = LEADING_HASHES.matcher(raw.trim()).replaceFirst("").trim();
    return WHITESPACE.matcher(trimmed).replaceAll("-").toLowerCase(Locale.ROOT);
  }

  /**
   * Every tag normalised, blanks dropped and duplicates removed, first occurrence kept.
   *
   * @param raw the tags as sent
   * @return the tags to store
   * @throws IllegalArgumentException when there are too many, or one is too long
   */
  static List<String> normaliseTags(final List<String> raw) {
    if (raw == null) {
      return List.of();
    }
    Set<String> tags = new LinkedHashSet<>();
    for (String tag : raw) {
      String normalised = normaliseTag(tag);
      if (normalised.isEmpty()) {
        continue;
      }
      if (normalised.length() > MAX_TAG_LENGTH) {
        throw new IllegalArgumentException(
            "A tag can be at most %d characters".formatted(MAX_TAG_LENGTH));
      }
      tags.add(normalised);
    }
    if (tags.size() > MAX_TAGS) {
      throw new IllegalArgumentException(
          "A snippet can have at most %d tags".formatted(MAX_TAGS));
    }
    return new ArrayList<>(tags);
  }
}
