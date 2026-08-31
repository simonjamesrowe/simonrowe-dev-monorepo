package com.simonrowe.factory.logwatch.signature;

import com.simonrowe.factory.logwatch.domain.LogLine;
import com.simonrowe.factory.logwatch.domain.LogSignature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reduces log lines to a stable signature, and groups lines sharing one.
 *
 * <p><strong>This is the feature; everything else is plumbing around it.</strong> Too strict and
 * every line looks new forever, so the module is pure noise. Too loose and unrelated faults merge,
 * so a real problem hides inside one an operator has already accepted.
 *
 * <p>Pure over strings: no clock, no client, no Spring. That is deliberate, so it can be tested
 * exhaustively from fixtures of real production log lines (NFR-004).
 *
 * <p>Order matters in {@link #normalise}. Each rule is applied to the output of the last, so the
 * specific patterns run before the general ones — normalising bare numbers first would eat the
 * digits that identify a timestamp or a UUID and leave the rest of those tokens behind as noise.
 */
public final class SignatureExtractor {

  private static final String ANSI = "\u001B\\[[0-9;]*[a-zA-Z]";

  private static final List<Rule> RULES =
      List.of(
          // Terminal control codes first: they can sit inside any other token and would
          // otherwise split one into pieces the later rules no longer recognise.
          new Rule(Pattern.compile(ANSI), ""),
          // ISO-8601, with or without fractional seconds and zone.
          new Rule(Pattern.compile(
              "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?"),
              "<TS>"),
          // nginx's common-log format: 31/Aug/2026:11:18:38 +0000
          new Rule(Pattern.compile(
              "\\d{2}/[A-Za-z]{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2}\\s*[+-]\\d{4}"), "<TS>"),
          // Bare clock times, for formats that log no date.
          new Rule(Pattern.compile("\\b\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b"), "<TS>"),
          new Rule(Pattern.compile(
              "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"),
              "<UUID>"),
          // Quoted paths before unquoted ones: the quotes are the reliable delimiter.
          new Rule(Pattern.compile("\"/[^\"\\s]*\""), "\"<PATH>\""),
          new Rule(Pattern.compile("'/[^'\\s]*'"), "'<PATH>'"),
          new Rule(Pattern.compile("(?<![\\w/])/[\\w.\\-/]{2,}"), "<PATH>"),
          // URLs before hosts, and hosts before bare numbers, or the port becomes <N>.
          new Rule(Pattern.compile("\\bhttps?://[^\\s\"']+"), "<URL>"),
          new Rule(Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b"), "<ADDR>"),
          // Long hex runs: container ids, digests, cursors. Bounded at 7 so ordinary words and
          // short hex-looking tokens (`abc`, `feed`) are not swallowed.
          new Rule(Pattern.compile("\\b[0-9a-fA-F]{7,}\\b"), "<HEX>"),
          // Byte/duration suffixes before the bare-number rule, so the unit is not stranded.
          new Rule(Pattern.compile("\\b\\d+(?:\\.\\d+)?(?:ms|s|m|h|ns|us|[KMGT]i?B)\\b"), "<QTY>"),
          new Rule(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), "<N>"));

  private SignatureExtractor() {
  }

  /**
   * Reduces one line to its signature.
   *
   * @param raw the line as shipped
   * @return the normalised form, invariant to timestamps, identifiers, paths and numbers
   */
  public static String normalise(final String raw) {
    if (raw == null) {
      return "";
    }
    String result = raw;
    for (Rule rule : RULES) {
      result = rule.pattern().matcher(result).replaceAll(rule.replacement());
    }
    return result.trim().replaceAll("\\s+", " ");
  }

  /**
   * Groups lines by signature, keeping one example and the observed time range of each group.
   *
   * <p>Grouping is per {@code (container, signature)}: the same normalised text from two different
   * containers is two problems with two different places to look, and merging them would hide one
   * inside the other. Insertion order is preserved so the result is deterministic for a given
   * input, which matters because the caller's cap decides what is dropped.
   *
   * @param lines the severity-classified lines in the window
   * @return one signature per distinct {@code (container, signature)} pair
   */
  public static List<LogSignature> group(final List<LogLine> lines) {
    Map<String, Accumulator> byKey = new LinkedHashMap<>();
    for (LogLine line : lines) {
      String signature = normalise(line.raw());
      String key = line.container() + "\u0000" + signature;
      byKey.computeIfAbsent(key, ignored -> new Accumulator(signature, line)).add(line);
    }
    List<LogSignature> grouped = new ArrayList<>(byKey.size());
    for (Accumulator accumulator : byKey.values()) {
      grouped.add(accumulator.toSignature());
    }
    return grouped;
  }

  private record Rule(Pattern pattern, String replacement) {
  }

  /** Mutable while grouping; converted to the immutable {@link LogSignature} at the end. */
  private static final class Accumulator {

    private final String signature;
    private final LogLine first;
    private com.simonrowe.factory.logwatch.domain.Severity severity;
    private java.time.Instant firstSeen;
    private java.time.Instant lastSeen;
    private int occurrences;

    private Accumulator(final String signature, final LogLine first) {
      this.signature = signature;
      this.first = first;
      this.severity = first.severity();
      this.firstSeen = first.timestamp();
      this.lastSeen = first.timestamp();
    }

    private void add(final LogLine line) {
      occurrences++;
      // Severity's declaration order is most-severe-first, so the smaller ordinal wins.
      if (line.severity().compareTo(severity) < 0) {
        severity = line.severity();
      }
      if (line.timestamp().isBefore(firstSeen)) {
        firstSeen = line.timestamp();
      }
      if (line.timestamp().isAfter(lastSeen)) {
        lastSeen = line.timestamp();
      }
    }

    private LogSignature toSignature() {
      return new LogSignature(
          signature, severity, first.container(), occurrences, firstSeen, lastSeen, first.raw());
    }
  }
}
