package com.simonrowe.factory.logwatch.signature;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces a log line to the identity of the code that emitted it.
 *
 * <p>This exists because grouping on the whole normalised line does not work.
 * {@link SignatureExtractor#normalise} masks timestamps, identifiers, numbers and paths, but not
 * free text inside a message, so {@code Agent 'ContentAggregation' must have...} and
 * {@code Agent 'WeeklyDigest' must have...} fingerprinted differently and filed two Linear tickets
 * for one startup failure. Six such forks were live in Triage on 2026-09-06.
 *
 * <p>Pure over strings, with no clock, no client and no Spring, on the same terms and for the same
 * reason as {@link SignatureExtractor}.
 *
 * <p>Handlers are tried in declaration order and each is guarded, so a line only reaches a handler
 * whose format it plausibly is. Returning empty is a first-class answer: the caller falls back to
 * the normalised whole line, which is exactly the behaviour that shipped before this class
 * existed, so an unrecognised format is never worse off than it was.
 */
public final class SourceKeyExtractor {

  /** The ECS JSON the backend emits. The {@code log.logger} value is the emitting class. */
  private static final Pattern ECS_LOGGER = Pattern.compile("\"logger\"\\s*:\\s*\"([^\"]+)\"");

  /**
   * Temporal's zap JSON. Its {@code msg} is a literal and is never interpolated — the variable
   * part of the event lives in the sibling {@code error} and {@code operation} fields — so it
   * identifies the emitting call site as well as a logger name would. {@code logging-call-at} is
   * the more obvious choice and is deliberately not used: it carries a source line number, so a
   * Temporal upgrade that shifts the file by one line would fork every ticket it emits.
   */
  private static final Pattern TEMPORAL_MSG =
      Pattern.compile("\"msg\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

  /** Alloy's logfmt. {@code component_id} names the pipeline component. */
  private static final Pattern LOGFMT_COMPONENT = Pattern.compile("(?:^|\\s)component_id=(\\S+)");

  /**
   * Spring Boot's default console layout. The logger is right-padded to a fixed width and then
   * separated by {@code " : "}, which is what makes whitespace-colon-whitespace a reliable
   * delimiter: the bracketed MDC context on the same line spells its colon as {@code "default": 1},
   * with no space before it, so it cannot match.
   */
  private static final Pattern SPRING_LOGGER = Pattern.compile("(?:^|\\s)([\\w.$]+)\\s+:\\s");

  /** A bare stack-trace head, as the deployer emits when a script fails. */
  private static final Pattern EXCEPTION_CLASS =
      Pattern.compile("^([a-zA-Z_$][\\w$]*(?:\\.[\\w$]+)+)\\s*:");

  private static final List<Handler> HANDLERS =
      List.of(
          new Handler(raw -> raw.startsWith("{") && raw.contains("\"logger\""), ECS_LOGGER),
          new Handler(raw -> raw.startsWith("{") && !raw.contains("\"logger\""), TEMPORAL_MSG),
          new Handler(raw -> raw.startsWith("ts=") || raw.contains(" level="), LOGFMT_COMPONENT),
          new Handler(raw -> raw.contains(" --- ["), SPRING_LOGGER),
          new Handler(raw -> !raw.startsWith("{"), EXCEPTION_CLASS));

  private SourceKeyExtractor() {
  }

  /**
   * Identifies the code that emitted a line.
   *
   * @param raw the line as shipped, before signature normalisation; may be null
   * @return the emitting source, or empty when no handler recognises the format
   */
  public static Optional<String> sourceKeyOf(final String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String line = raw.strip();
    for (Handler handler : HANDLERS) {
      if (!handler.guard().test(line)) {
        continue;
      }
      Matcher matcher = handler.pattern().matcher(line);
      if (matcher.find()) {
        String key = matcher.group(1).strip();
        if (!key.isEmpty()) {
          return Optional.of(key);
        }
      }
    }
    return Optional.empty();
  }

  private record Handler(Predicate<String> guard, Pattern pattern) {
  }
}
