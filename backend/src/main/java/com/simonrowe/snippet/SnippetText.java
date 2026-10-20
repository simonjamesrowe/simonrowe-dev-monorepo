package com.simonrowe.snippet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Block;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.jsoup.Jsoup;

/**
 * The text rules snippets depend on: what a summary or a blog post reads as once rendered,
 * whether a quote really came from it, and what a tag looks like.
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
  // The blog page renders with remark-gfm and rehype-raw, so a post is parsed with tables and
  // strikethrough, and its raw HTML is read for the text a browser would show.
  private static final Parser POST_PARSER = Parser.builder()
      .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create()))
      .build();
  private static final String MERMAID = "mermaid";

  /** How a source is rendered in the browser, which decides what its text is. */
  private enum Rendering {
    /** The summary drawer: plain markdown, raw HTML dropped. */
    SUMMARY,
    /** The blog page: GFM, raw HTML shown, images and Mermaid diagrams carry no text. */
    POST
  }

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
    return rendered(markdown, Rendering.SUMMARY);
  }

  /**
   * A blog post as a reader sees it on the blog page. Unlike a summary, its raw HTML is shown
   * (the page runs rehype-raw), tables are tables, an image's alt text is not on the page,
   * and a Mermaid block is drawn as a diagram, so its source is not text a reader can select.
   *
   * <p>Not modelled, and accepted: a GFM footnote marker ({@code [^1]}) reads as the literal
   * marker here and as a number on the page, so a quote spanning one would be refused. No post
   * used a footnote on 2026-10-20, and a quote either side of a marker would still work.
   *
   * @param markdown the post's content
   * @return its text, whitespace collapsed
   */
  static String renderedPostText(final String markdown) {
    return rendered(markdown, Rendering.POST);
  }

  private static String rendered(final String markdown, final Rendering rendering) {
    if (markdown == null || markdown.isBlank()) {
      return "";
    }
    Parser parser = rendering == Rendering.POST ? POST_PARSER : PARSER;
    StringBuilder text = new StringBuilder();
    collect(parser.parse(markdown), text, rendering);
    return normalise(text.toString());
  }

  private static void collect(final Node node, final StringBuilder text,
      final Rendering rendering) {
    boolean post = rendering == Rendering.POST;
    switch (node) {
      case Text literal -> text.append(literal.getLiteral());
      case Code code -> text.append(code.getLiteral());
      case FencedCodeBlock fenced when post && isMermaid(fenced) -> text.append(' ');
      case FencedCodeBlock fenced -> text.append(' ').append(fenced.getLiteral()).append(' ');
      case IndentedCodeBlock indented ->
          text.append(' ').append(indented.getLiteral()).append(' ');
      case SoftLineBreak ignored -> text.append(' ');
      case HardLineBreak ignored -> text.append(' ');
      case Image ignored when post -> {
        // Alt text is an attribute on the page, not text a reader can select.
      }
      case HtmlBlock html when post ->
          text.append(' ').append(Jsoup.parse(html.getLiteral()).text()).append(' ');
      case HtmlInline html when post -> text.append(Jsoup.parse(html.getLiteral()).text());
      default -> {
        // A block boundary is a paragraph break in the browser's quote, so it must not
        // glue the last word of one block to the first of the next. A table cell is a
        // block in the browser too, though commonmark does not make it one.
        boolean block = node instanceof Block || node instanceof TableCell;
        if (block) {
          text.append(' ');
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
          collect(child, text, rendering);
        }
        if (block) {
          text.append(' ');
        }
      }
    }
  }

  /** The blog page's code renderer draws a block as a diagram when its language is mermaid. */
  private static boolean isMermaid(final FencedCodeBlock fenced) {
    String info = fenced.getInfo() == null ? "" : fenced.getInfo().trim();
    int space = info.indexOf(' ');
    return MERMAID.equals(space < 0 ? info : info.substring(0, space));
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
   * Whether a quote occurs in a blog post, ignoring whitespace differences.
   *
   * @param postMarkdown the post's content
   * @param exact the quote
   * @return true when the post contains it
   */
  static boolean postContains(final String postMarkdown, final String exact) {
    String quote = normalise(exact);
    return !quote.isEmpty() && renderedPostText(postMarkdown).contains(quote);
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
