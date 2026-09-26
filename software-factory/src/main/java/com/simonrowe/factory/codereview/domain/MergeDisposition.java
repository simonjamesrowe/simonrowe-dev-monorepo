package com.simonrowe.factory.codereview.domain;

import java.util.Collection;
import java.util.List;

/**
 * Who may merge a change, decided from nothing but its paths.
 *
 * <p><b>A port of {@code scripts/classify-change.sh}, and it must stay one.</b> The script is what
 * a human or the {@code pr-review-loop} skill runs; this is what the reviewer arms auto-merge
 * from, because the reviewer runs in a container with no checkout to diff. Both test suites read
 * {@code scripts/test/fixtures/merge-disposition-cases.tsv}, so a rule changed in one copy and not
 * the other fails a build rather than drifting.
 *
 * <p>Patterns use the script's {@code case} semantics, where {@code *} matches any run of
 * characters <em>including</em> {@code /}. So {@code frontend/*.config.*} catches
 * {@code frontend/src/theme.config.ts} as well as {@code frontend/vite.config.ts}. That is the
 * script's behaviour and therefore this one's; a stricter reading here would let the factory arm a
 * change the script calls manual.
 */
public enum MergeDisposition {
  AUTO_MERGE,
  UX_REVIEW,
  MANUAL;

  /**
   * Rule 1 — needs a human. Highest precedence, and it outranks rule 3 on purpose: an auto-merge
   * to {@code main} triggers Publish, which triggers an unattended deploy against the Pi.
   */
  private static final List<String> MANUAL_PATTERNS =
      List.of(
          "docker-compose*.yml",
          "docker-compose*.yaml",
          "scripts/*",
          "config/*",
          ".github/*",
          "gradlew",
          "gradlew.bat",
          "gradle/*",
          "gradle.properties",
          "settings.gradle*",
          "build.gradle*",
          "frontend/*.config.*",
          "frontend/package.json",
          "frontend/package-lock.json");

  /** Excluded from rule 2 before it is tested: test code ships no pixel. */
  private static final List<String> NOT_UX_PATTERNS = List.of("frontend/tests/*", "frontend/e2e/*");

  /** Rule 2 — changes a visitor can see. */
  private static final List<String> UX_PATTERNS =
      List.of("frontend/src/*", "frontend/index.html", "frontend/public/*");

  /** Rule 3 — cannot change a shipped pixel or production infrastructure. */
  private static final List<String> AUTO_MERGE_PATTERNS =
      List.of(
          "backend/*",
          "software-factory/*",
          "docs/*",
          "specs/*",
          "frontend/tests/*",
          "frontend/e2e/*");

  /**
   * The disposition of a whole change, plus the path that decided it.
   *
   * @param decidingPath the path that forced {@code MANUAL} or {@code UX_REVIEW}; null for
   *     {@code AUTO_MERGE} (every path qualified) and for an empty change
   */
  public record Classification(MergeDisposition disposition, String decidingPath) {
  }

  /**
   * Classifies a change.
   *
   * <p>An empty change is {@code MANUAL}: arming a merge nobody can see is the same mistake as
   * rule 4.
   */
  public static Classification classify(final Collection<String> paths) {
    MergeDisposition result = AUTO_MERGE;
    String uxPath = null;
    boolean sawAny = false;
    for (String path : paths) {
      if (path == null || path.isEmpty()) {
        continue;
      }
      sawAny = true;
      if (matchesAny(path, MANUAL_PATTERNS)) {
        return new Classification(MANUAL, path);
      } else if (isUx(path)) {
        result = UX_REVIEW;
        if (uxPath == null) {
          uxPath = path;
        }
      } else if (!isAutoMerge(path)) {
        // Rule 4 — an unrecognised path is manual, never auto-merge, so a directory added later
        // defaults to needing a human instead of inheriting merge rights nobody granted it.
        return new Classification(MANUAL, path);
      }
    }
    if (!sawAny) {
      return new Classification(MANUAL, null);
    }
    return new Classification(result, uxPath);
  }

  private static boolean isUx(final String path) {
    return !matchesAny(path, NOT_UX_PATTERNS) && matchesAny(path, UX_PATTERNS);
  }

  private static boolean isAutoMerge(final String path) {
    if (matchesAny(path, AUTO_MERGE_PATTERNS)) {
      return true;
    }
    // Root-level markdown only: any other nested path is unrecognised.
    return path.indexOf('/') < 0 && path.endsWith(".md");
  }

  private static boolean matchesAny(final String path, final List<String> patterns) {
    for (String pattern : patterns) {
      if (glob(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Shell {@code case}-style match where {@code *} is the only metacharacter and matches any run
   * of characters, slashes included.
   *
   * <p>A two-pointer match that only ever backtracks to the most recent star, rather than a
   * translated regex: its cost is bounded by pattern length times path length, so no path shape
   * can make it backtrack catastrophically.
   */
  static boolean glob(final String pattern, final String text) {
    int p = 0;
    int t = 0;
    int star = -1;
    int mark = 0;
    while (t < text.length()) {
      if (p < pattern.length() && pattern.charAt(p) == '*') {
        star = p++;
        mark = t;
      } else if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) {
        p++;
        t++;
      } else if (star >= 0) {
        p = star + 1;
        t = ++mark;
      } else {
        return false;
      }
    }
    while (p < pattern.length() && pattern.charAt(p) == '*') {
      p++;
    }
    return p == pattern.length();
  }
}
