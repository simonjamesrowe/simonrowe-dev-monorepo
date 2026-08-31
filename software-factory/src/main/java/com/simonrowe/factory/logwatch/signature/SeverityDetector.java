package com.simonrowe.factory.logwatch.signature;

import com.simonrowe.factory.logwatch.domain.Severity;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides whether a log line is an error or a warning, without assuming a single log format.
 *
 * <p>FR-002 forbids assuming one format, and the stack proves the point: nginx access lines carry
 * no level at all, the JVM services log a bare {@code ERROR} token, Kafka uses {@code [ERROR]},
 * ClickHouse uses {@code <Error>} and Alloy uses {@code level=error}. A single permissive regex
 * over the whole line classifies an nginx request for {@code /ERROR} as an error.
 *
 * <p>Unmatched lines are <strong>excluded</strong>, never defaulted to {@code WARN}. The
 * alternative floods the first runs with every unparsed line from every container, which is
 * exactly the noise that makes a module something to ignore.
 *
 * <p>Pure and side-effect free, so it is tested exhaustively from fixtures with no stubbing.
 */
public final class SeverityDetector {

  /**
   * Matchers in precedence order, first match wins.
   *
   * <p>Each is anchored to the shape of a level token rather than to the word appearing anywhere,
   * which is what stops a URL path or a message body being read as a level.
   */
  private static final List<Matcher> MATCHERS =
      List.of(
          // logfmt: Alloy, and anything else built on go-kit. `level=error`
          new Matcher(Pattern.compile("(?:^|\\s)level=error(?:\\s|$)", Pattern.CASE_INSENSITIVE),
              Severity.ERROR),
          new Matcher(Pattern.compile("(?:^|\\s)level=warn(?:ing)?(?:\\s|$)",
              Pattern.CASE_INSENSITIVE), Severity.WARN),
          // ClickHouse: `<Error>` / `<Warning>`
          new Matcher(Pattern.compile("<Error>"), Severity.ERROR),
          new Matcher(Pattern.compile("<Warning>"), Severity.WARN),
          // Kafka, Elasticsearch and friends: `[ERROR]`, `[WARN ]`
          new Matcher(Pattern.compile("\\[\\s*ERROR\\s*]"), Severity.ERROR),
          new Matcher(Pattern.compile("\\[\\s*WARN(?:ING)?\\s*]"), Severity.WARN),
          // JSON structured logs: `"level":"error"`
          new Matcher(Pattern.compile("\"level\"\\s*:\\s*\"(?:error|fatal)\"",
              Pattern.CASE_INSENSITIVE), Severity.ERROR),
          new Matcher(Pattern.compile("\"level\"\\s*:\\s*\"warn(?:ing)?\"",
              Pattern.CASE_INSENSITIVE), Severity.WARN),
          // MongoDB's structured log: `"s":"E"` / `"s":"W"`
          new Matcher(Pattern.compile("\"s\"\\s*:\\s*\"[EF]\""), Severity.ERROR),
          new Matcher(Pattern.compile("\"s\"\\s*:\\s*\"W\""), Severity.WARN),
          // Spring Boot's default console layout: a whitespace-delimited level column.
          new Matcher(Pattern.compile("\\s(?:ERROR|FATAL)\\s+\\d+\\s+---"), Severity.ERROR),
          new Matcher(Pattern.compile("\\sWARN\\s+\\d+\\s+---"), Severity.WARN),
          // Bare level token bounded by whitespace, as a last resort. Deliberately last: it is
          // the loosest rule here and would otherwise shadow the precise ones above.
          new Matcher(Pattern.compile("(?:^|\\s)(?:ERROR|FATAL)(?::|\\s|$)"), Severity.ERROR),
          new Matcher(Pattern.compile("(?:^|\\s)WARN(?:ING)?(?::|\\s|$)"), Severity.WARN));

  private SeverityDetector() {
  }

  /**
   * Classifies one raw log line.
   *
   * @param raw the line as shipped by Alloy
   * @return the detected severity, or empty when the line is not a candidate at all
   */
  public static Optional<Severity> detect(final String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    for (Matcher matcher : MATCHERS) {
      if (matcher.pattern().matcher(raw).find()) {
        return Optional.of(matcher.severity());
      }
    }
    return Optional.empty();
  }

  private record Matcher(Pattern pattern, Severity severity) {
  }
}
