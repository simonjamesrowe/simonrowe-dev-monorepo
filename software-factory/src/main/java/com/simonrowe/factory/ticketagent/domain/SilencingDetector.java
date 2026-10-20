package com.simonrowe.factory.ticketagent.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the parts of a diff that quiet something rather than fix it.
 *
 * <p>Muting a log line is the obvious fix for half of what log watch files, and it is also exactly
 * what a crafted log line would ask an agent to do: a ticket quotes production text a visitor can
 * influence. So any diff that adds a mute rule, lowers a logger, disables a test or suppresses a
 * warning is labelled {@code no-auto-merge}, and a person merges it. The agent can still propose
 * one; it just cannot ship one alone.
 *
 * <p>Plain string tests on each added line, never a pattern across the diff. The diff is unbounded
 * input, and a regex over it is a backtracking risk for no gain: every rule here is "does this one
 * line contain this token".
 */
public final class SilencingDetector {

  private static final String ADDED_FILE = "+++ b/";

  /** Tokens that silence something wherever they appear. */
  private static final List<String> ANYWHERE =
      List.of(
          "@SuppressWarnings",
          "NOSONAR",
          "eslint-disable",
          "@ts-ignore",
          "@ts-expect-error",
          "@Disabled",
          "@Ignore",
          "it.skip(",
          "describe.skip(",
          "test.skip(",
          "xit(",
          "xdescribe(");

  /** Logger levels that hide WARN and below. */
  private static final Set<String> QUIET_LEVELS = Set.of("off", "error", "fatal");

  private SilencingDetector() {
  }

  /**
   * The reasons a diff quiets something, one per file and kind.
   *
   * @param unifiedDiff {@code git diff} output
   * @return human-readable reasons; empty when the diff silences nothing
   */
  public static List<String> reasons(final String unifiedDiff) {
    Set<String> reasons = new LinkedHashSet<>();
    String file = null;
    for (String line : unifiedDiff.split("\n", -1)) {
      if (line.startsWith(ADDED_FILE)) {
        file = line.substring(ADDED_FILE.length()).trim();
        continue;
      }
      if (file == null || !line.startsWith("+") || line.startsWith("+++")) {
        continue;
      }
      reasonFor(file, line.substring(1)).ifPresent(reasons::add);
    }
    return new ArrayList<>(reasons);
  }

  private static Optional<String> reasonFor(final String file, final String added) {
    for (String token : ANYWHERE) {
      if (added.contains(token)) {
        return Optional.of("`%s` adds `%s`".formatted(file, token));
      }
    }
    String lowerFile = file.toLowerCase(Locale.ROOT);
    String trimmed = added.trim();
    if (lowerFile.endsWith(".yml") || lowerFile.endsWith(".yaml")) {
      if (trimmed.startsWith("contains:") || trimmed.startsWith("- contains:")) {
        return Optional.of("`%s` adds a log-watch mute rule".formatted(file));
      }
      if (setsQuietLevel(trimmed)) {
        return Optional.of("`%s` quiets a logger".formatted(file));
      }
    }
    if (lowerFile.endsWith(".properties") && trimmed.startsWith("logging.level.")) {
      return Optional.of("`%s` changes a logger level".formatted(file));
    }
    if (lowerFile.endsWith(".xml") && lowerFile.contains("logback")) {
      String lower = trimmed.toLowerCase(Locale.ROOT);
      for (String level : QUIET_LEVELS) {
        if (lower.contains("level=\"" + level + "\"")) {
          return Optional.of("`%s` quiets a logger".formatted(file));
        }
      }
    }
    return Optional.empty();
  }

  /** {@code some.Logger: ERROR}, or {@code "[a$b]": off}: a key whose value is a quiet level. */
  private static boolean setsQuietLevel(final String trimmed) {
    int colon = trimmed.lastIndexOf(':');
    if (colon <= 0 || colon == trimmed.length() - 1) {
      return false;
    }
    String value = trimmed.substring(colon + 1).trim();
    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
      value = value.substring(1, value.length() - 1);
    }
    return QUIET_LEVELS.contains(value.toLowerCase(Locale.ROOT));
  }
}
