package com.simonrowe.narration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Splits a narration script into pieces small enough for Google's synchronous synthesis
 * endpoint, which caps input at a few thousand UTF-8 bytes per request.
 *
 * <p>Chunking is what lets the synchronous endpoint cover scripts of any length. That
 * matters because the long-audio endpoint — the only other option — currently refuses MP3
 * and emits LINEAR16, which would multiply stored audio roughly tenfold.
 *
 * <p>Splitting prefers sentence ends, then any whitespace, and only falls back to a hard
 * byte cut for a single "word" longer than the limit (a pathological URL, say). The
 * boundary choice is audible: cutting mid-sentence puts an unnatural stop into the speech,
 * whereas a sentence end already carries one.
 *
 * <p>Measured in UTF-8 <em>bytes</em>, not characters. Prose with curly quotes, em dashes
 * and accented names runs materially longer encoded than its character count suggests, and
 * the provider's limit is a byte limit.
 */
@Component
public class NarrationScriptChunker {

  /** End of sentence followed by whitespace. */
  private static final Pattern SENTENCE_END = Pattern.compile("[.!?][\"')\\]]?\\s+");

  /**
   * Splits the script into chunks, each within {@code maxBytes} when UTF-8 encoded.
   *
   * @param script the full narration script
   * @param maxBytes the per-chunk ceiling in UTF-8 bytes
   * @return the chunks in order; a single-element list when the script already fits, and an
   *     empty list for a blank script
   */
  public List<String> chunk(final String script, final int maxBytes) {
    if (script == null || script.isBlank()) {
      return List.of();
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    String trimmed = script.trim();
    if (utf8Length(trimmed) <= maxBytes) {
      return List.of(trimmed);
    }

    List<String> chunks = new ArrayList<>();
    String remaining = trimmed;
    while (!remaining.isEmpty()) {
      if (utf8Length(remaining) <= maxBytes) {
        chunks.add(remaining);
        break;
      }
      int cut = splitPoint(remaining, maxBytes);
      chunks.add(remaining.substring(0, cut).trim());
      remaining = remaining.substring(cut).trim();
    }
    chunks.removeIf(String::isBlank);
    return List.copyOf(chunks);
  }

  /**
   * The index to cut at: the last sentence end within the byte budget, else the last
   * whitespace, else the largest prefix that still fits.
   */
  private int splitPoint(final String text, final int maxBytes) {
    int limit = charsWithinBytes(text, maxBytes);

    int lastSentenceEnd = -1;
    Matcher matcher = SENTENCE_END.matcher(text);
    while (matcher.find() && matcher.end() <= limit) {
      lastSentenceEnd = matcher.end();
    }
    if (lastSentenceEnd > 0) {
      return lastSentenceEnd;
    }

    int lastSpace = text.lastIndexOf(' ', limit - 1);
    if (lastSpace > 0) {
      return lastSpace;
    }
    // A single token longer than the whole budget. Cut it rather than loop forever.
    return limit;
  }

  /**
   * The largest character count whose UTF-8 encoding fits in {@code maxBytes}, never
   * splitting a surrogate pair.
   */
  private int charsWithinBytes(final String text, final int maxBytes) {
    int bytes = 0;
    int index = 0;
    while (index < text.length()) {
      int codePoint = text.codePointAt(index);
      int width = utf8Width(codePoint);
      if (bytes + width > maxBytes) {
        break;
      }
      bytes += width;
      index += Character.charCount(codePoint);
    }
    return Math.max(index, 1);
  }

  private static int utf8Width(final int codePoint) {
    if (codePoint <= 0x7f) {
      return 1;
    }
    if (codePoint <= 0x7ff) {
      return 2;
    }
    if (codePoint <= 0xffff) {
      return 3;
    }
    return 4;
  }

  /**
   * UTF-8 byte length of a string.
   *
   * @param text the string to measure
   * @return the byte length
   */
  public static int utf8Length(final String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }
}
