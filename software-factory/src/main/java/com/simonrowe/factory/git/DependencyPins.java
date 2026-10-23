package com.simonrowe.factory.git;

import com.simonrowe.factory.cvefix.domain.MavenVersion;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a pull request's build-file changes do nothing but move dependency versions.
 *
 * <p>This is what lets a CVE bump merge itself. Build and dependency files otherwise always need a
 * person, because a build script is code CI runs. A change that only replaces one version string
 * with another, in the three files this repository pins versions in, cannot add a build step,
 * a repository, a plugin or a new library. It can only pick a different release of something
 * already in the graph.
 *
 * <p>The rule is mechanical, never a judgement about the diff:
 *
 * <ul>
 *   <li>Only {@link #PIN_FILES}, only modified (not added, deleted or renamed), and GitHub must
 *       have supplied the patch. A missing patch (GitHub omits it for large diffs) is a no.
 *   <li>A removed line and an added line pair up when they are identical once every version token
 *       is masked. That covers a version changed anywhere a version can sit: a catalogue entry,
 *       an inline library table, an {@code ext["…"]} override or a constraint coordinate. The new
 *       version must look like one: digits, a dot, then version characters.
 *   <li>An added line with no partner may only be a new catalogue version, a new
 *       {@code ext["…"]} override, or a new {@code implementation("g:a:v")} directly inside a
 *       {@code constraints {}} block (checked against the file at the pull request's head, so a
 *       coordinate added to {@code dependencies {}}, which would add a library, is refused).
 *   <li>Blank and comment lines may come and go. Every other removal must be paired: deleting a
 *       pin is not a version change.
 *   <li>A paired change may only raise a version, never lower it, so a mislabelled ticket cannot
 *       use this to put back a vulnerable release.
 * </ul>
 *
 * <p>Accepted limit: a new release of an existing library runs in CI and then in production, and
 * nothing here can tell a good release from a compromised one. That is the same exposure as any
 * automated dependency bump, and it is why this applies only to the factory's own CVE repair pull
 * requests, after CI and the reviewer pass, with the post-deploy log scan watching what ships.
 *
 * <p>Every pattern is anchored and possessive over disjoint classes, and lines are capped at
 * {@link #MAX_LINE} characters before matching, so no patch can make a match backtrack.
 */
public final class DependencyPins {

  /** The version catalogue. */
  public static final String VERSION_CATALOG = "gradle/libs.versions.toml";

  /** Where this repository pins dependency versions. */
  public static final List<String> PIN_FILES =
      List.of(VERSION_CATALOG, "build.gradle.kts", "backend/build.gradle.kts");

  static final int MAX_LINE = 400;

  /** A quoted string's last segment, straight after the quote or a colon, starting with a digit. */
  private static final Pattern VERSION_TOKEN =
      Pattern.compile("(?<=[\":])[0-9][A-Za-z0-9._+-]*+(?=\")");

  /** What a new version must look like: {@code 4.1.118.Final}, {@code 2.21.7}, {@code 1.0-RC1}. */
  private static final Pattern VERSION = Pattern.compile("[0-9]++\\.[A-Za-z0-9._+-]*+");

  private static final Pattern BLANK = Pattern.compile("\\s*+");
  private static final Pattern KTS_COMMENT = Pattern.compile("\\s*+//.*+");
  private static final Pattern TOML_COMMENT = Pattern.compile("\\s*+#.*+");
  private static final Pattern TOML_VERSION =
      Pattern.compile("\\s*+[A-Za-z0-9_-]++\\s*+=\\s*+\"([^\"]*+)\"\\s*+");
  private static final Pattern TOML_LIBRARY =
      Pattern.compile("\\s*+[A-Za-z0-9_-]++\\s*+=\\s*+\\{[^{}]*+}\\s*+");
  private static final Pattern KTS_EXT =
      Pattern.compile("\\s*+ext\\[\"[A-Za-z0-9._-]++\"]\\s*+=\\s*+\"([^\"]*+)\"\\s*+");
  private static final Pattern KTS_CONSTRAINT =
      Pattern.compile(
          "\\s*+implementation\\(\"[A-Za-z0-9._-]++:[A-Za-z0-9._-]++:([^\"]*+)\"\\)\\s*+");
  private static final Pattern HUNK =
      Pattern.compile("@@ -[0-9]++(?:,[0-9]++)? \\+([0-9]++)(?:,[0-9]++)? @@.*+");

  private DependencyPins() {
  }

  /**
   * One changed file, as GitHub's pull request file listing reports it.
   *
   * @param path the file's path at the head
   * @param previousPath the old path of a rename, otherwise null or empty
   * @param status GitHub's status: {@code added}, {@code modified}, {@code removed},
   *     {@code renamed}...
   * @param patch the unified diff GitHub supplies, or null when it omitted one
   */
  public record FileDiff(String path, String previousPath, String status, String patch) {
  }

  /**
   * The answer, with the reason when it is no.
   *
   * @param versionsOnly true when every change is a version change
   * @param reason a sentence naming the file and line that failed; empty when {@code versionsOnly}
   */
  public record Verdict(boolean versionsOnly, String reason) {

    static Verdict yes() {
      return new Verdict(true, "");
    }

    static Verdict no(final String reason) {
      return new Verdict(false, reason);
    }
  }

  /**
   * Whether a path is one of {@link #PIN_FILES}.
   *
   * @param path a repository-relative path
   * @return true for a pin file
   */
  public static boolean isPinFile(final String path) {
    return path != null && PIN_FILES.contains(path);
  }

  /**
   * Checks that every file is a pin file whose changes only move versions.
   *
   * @param files the changed files; must not be empty
   * @param headContent the file's content at the pull request's head, by path, or null when it
   *     cannot be read; only asked for when a constraint line is added
   * @return the verdict
   */
  public static Verdict check(final List<FileDiff> files, final UnaryOperator<String> headContent) {
    if (files == null || files.isEmpty()) {
      return Verdict.no("no build files changed");
    }
    for (FileDiff file : files) {
      Verdict verdict = checkFile(file, headContent);
      if (!verdict.versionsOnly()) {
        return verdict;
      }
    }
    return Verdict.yes();
  }

  private static Verdict checkFile(final FileDiff file, final UnaryOperator<String> headContent) {
    String path = file.path();
    if (!isPinFile(path)) {
      return Verdict.no("`" + path + "` is not a file versions are pinned in");
    }
    if (!"modified".equals(file.status())
        || (file.previousPath() != null && !file.previousPath().isEmpty())) {
      return Verdict.no("`" + path + "` was " + file.status() + ", not just edited");
    }
    if (file.patch() == null || file.patch().isEmpty()) {
      return Verdict.no("GitHub supplied no diff for `" + path + "`");
    }
    List<Change> changes = parse(file.patch());
    if (changes == null) {
      return Verdict.no("the diff of `" + path + "` could not be read");
    }
    boolean catalogue = VERSION_CATALOG.equals(path);
    Map<String, Deque<String>> removed = new HashMap<>();
    for (Change change : changes) {
      if (change.added()) {
        continue;
      }
      Kind kind = kindOf(change.text(), catalogue);
      if (kind == Kind.OTHER) {
        return Verdict.no(refusal(path, "removes", change.text()));
      }
      if (kind != Kind.NOISE) {
        removed.computeIfAbsent(mask(change.text()), key -> new ArrayDeque<>())
            .add(change.text());
      }
    }
    for (Change change : changes) {
      if (!change.added()) {
        continue;
      }
      Kind kind = kindOf(change.text(), catalogue);
      if (kind == Kind.NOISE) {
        continue;
      }
      if (kind == Kind.OTHER) {
        return Verdict.no(refusal(path, "adds", change.text()));
      }
      if (!newVersionsLookRight(change.text())) {
        return Verdict.no(refusal(path, "sets something other than a version in", change.text()));
      }
      String masked = mask(change.text());
      Deque<String> partners = removed.get(masked);
      if (partners != null) {
        String before = partners.poll();
        if (partners.isEmpty()) {
          removed.remove(masked);
        }
        if (!raisesOnly(before, change.text())) {
          return Verdict.no(refusal(path, "lowers a version in", change.text()));
        }
        continue;
      }
      Verdict unpaired = unpairedAddition(path, change, kind, headContent);
      if (!unpaired.versionsOnly()) {
        return unpaired;
      }
    }
    if (!removed.isEmpty()) {
      return Verdict.no("`" + path + "` removes a pinned version rather than changing it");
    }
    return Verdict.yes();
  }

  private static Verdict unpairedAddition(
      final String path,
      final Change change,
      final Kind kind,
      final UnaryOperator<String> headContent) {
    return switch (kind) {
      case CATALOGUE_VERSION, OVERRIDE -> Verdict.yes();
      case CONSTRAINT -> {
        String content = headContent == null ? null : headContent.apply(path);
        if (content != null && directlyInside(content, change.headLine(), "constraints")) {
          yield Verdict.yes();
        }
        yield Verdict.no(
            "`%s` line %d adds a library outside a `constraints {}` block"
                .formatted(path, change.headLine()));
      }
      default -> Verdict.no(refusal(path, "adds", change.text()));
    };
  }

  private static String refusal(final String path, final String verb, final String line) {
    String shown = line.strip();
    if (shown.length() > 120) {
      shown = shown.substring(0, 120) + "…";
    }
    return "`%s` %s more than a version: `%s`".formatted(path, verb, shown);
  }

  private enum Kind {
    NOISE,
    CATALOGUE_VERSION,
    CATALOGUE_LIBRARY,
    OVERRIDE,
    CONSTRAINT,
    OTHER
  }

  private static Kind kindOf(final String line, final boolean catalogue) {
    if (line.length() > MAX_LINE) {
      return Kind.OTHER;
    }
    if (BLANK.matcher(line).matches()) {
      return Kind.NOISE;
    }
    if (catalogue) {
      if (TOML_COMMENT.matcher(line).matches()) {
        return Kind.NOISE;
      }
      Matcher version = TOML_VERSION.matcher(line);
      if (version.matches()) {
        return VERSION.matcher(version.group(1)).matches() ? Kind.CATALOGUE_VERSION : Kind.OTHER;
      }
      return TOML_LIBRARY.matcher(line).matches() ? Kind.CATALOGUE_LIBRARY : Kind.OTHER;
    }
    if (KTS_COMMENT.matcher(line).matches()) {
      return Kind.NOISE;
    }
    Matcher override = KTS_EXT.matcher(line);
    if (override.matches()) {
      return VERSION.matcher(override.group(1)).matches() ? Kind.OVERRIDE : Kind.OTHER;
    }
    Matcher constraint = KTS_CONSTRAINT.matcher(line);
    if (constraint.matches()) {
      return VERSION.matcher(constraint.group(1)).matches() ? Kind.CONSTRAINT : Kind.OTHER;
    }
    return Kind.OTHER;
  }

  /** The line with every version token replaced, so two lines differing only in versions match. */
  static String mask(final String line) {
    return VERSION_TOKEN.matcher(line.strip()).replaceAll("<version>");
  }

  /**
   * Whether every version in {@code after} is at least the one in the same place in
   * {@code before}. A downgrade is the one version-only change that can make things worse: it can
   * put back exactly the release an advisory was about.
   */
  private static boolean raisesOnly(final String before, final String after) {
    Matcher old = VERSION_TOKEN.matcher(before);
    Matcher updated = VERSION_TOKEN.matcher(after);
    while (old.find() && updated.find()) {
      if (MavenVersion.of(updated.group()).compareTo(MavenVersion.of(old.group())) < 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean newVersionsLookRight(final String line) {
    Matcher token = VERSION_TOKEN.matcher(line);
    while (token.find()) {
      if (!VERSION.matcher(token.group()).matches()) {
        return false;
      }
    }
    return true;
  }

  /**
   * One added or removed line.
   *
   * @param added true for {@code +}, false for {@code -}
   * @param headLine the line's number in the head file; meaningful for added lines only
   * @param text the line without its marker
   */
  private record Change(boolean added, int headLine, String text) {
  }

  /** The changes in a GitHub patch, or null when it is not a well-formed run of hunks. */
  private static List<Change> parse(final String patch) {
    List<Change> changes = new ArrayList<>();
    int headLine = -1;
    for (String line : patch.split("\n", -1)) {
      if (line.startsWith("@@")) {
        if (line.length() > MAX_LINE) {
          return null;
        }
        Matcher hunk = HUNK.matcher(line);
        if (!hunk.matches()) {
          return null;
        }
        headLine = Integer.parseInt(hunk.group(1));
        continue;
      }
      if (headLine < 0) {
        if (line.isEmpty()) {
          continue;
        }
        return null;
      }
      if (line.isEmpty() || line.charAt(0) == '\\') {
        // A trailing split artefact, or "\ No newline at end of file".
        continue;
      }
      switch (line.charAt(0)) {
        case '+' -> changes.add(new Change(true, headLine++, line.substring(1)));
        case '-' -> changes.add(new Change(false, -1, line.substring(1)));
        case ' ' -> headLine++;
        default -> {
          return null;
        }
      }
    }
    return headLine < 0 ? null : changes;
  }

  /**
   * Whether a line of a Kotlin build script sits directly inside a block of the given name.
   *
   * <p>A small scanner rather than a regex: it skips strings, raw strings and comments so braces
   * inside them do not count, and names each block after the identifier just before its brace.
   * A line inside a raw string or a block comment is never inside anything.
   */
  static boolean directlyInside(final String content, final int targetLine, final String block) {
    if (targetLine <= 1) {
      return false;
    }
    Deque<String> stack = new ArrayDeque<>();
    String lastIdentifier = null;
    int line = 1;
    int i = 0;
    int length = content.length();
    while (i < length) {
      char c = content.charAt(i);
      if (c == '\n') {
        line++;
        i++;
        if (line == targetLine) {
          return block.equals(stack.peek());
        }
        continue;
      }
      if (c == '/' && i + 1 < length && content.charAt(i + 1) == '/') {
        while (i < length && content.charAt(i) != '\n') {
          i++;
        }
        continue;
      }
      if (c == '/' && i + 1 < length && content.charAt(i + 1) == '*') {
        int end = content.indexOf("*/", i + 2);
        int stop = end < 0 ? length : end + 2;
        line += newlines(content, i, stop);
        if (line >= targetLine) {
          return false;
        }
        i = stop;
        continue;
      }
      if (c == '"') {
        int stop = content.startsWith("\"\"\"", i)
            ? rawStringEnd(content, i)
            : stringEnd(content, i);
        line += newlines(content, i, stop);
        if (line >= targetLine) {
          return false;
        }
        i = stop;
        lastIdentifier = null;
        continue;
      }
      if (Character.isJavaIdentifierStart(c)) {
        int start = i;
        while (i < length && Character.isJavaIdentifierPart(content.charAt(i))) {
          i++;
        }
        lastIdentifier = content.substring(start, i);
        continue;
      }
      if (c == '{') {
        stack.push(lastIdentifier == null ? "" : lastIdentifier);
        lastIdentifier = null;
      } else if (c == '}') {
        if (!stack.isEmpty()) {
          stack.pop();
        }
        lastIdentifier = null;
      } else if (!Character.isWhitespace(c)) {
        lastIdentifier = null;
      }
      i++;
    }
    return false;
  }

  private static int rawStringEnd(final String content, final int start) {
    int end = content.indexOf("\"\"\"", start + 3);
    return end < 0 ? content.length() : end + 3;
  }

  private static int stringEnd(final String content, final int start) {
    int i = start + 1;
    while (i < content.length()) {
      char c = content.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == '"' || c == '\n') {
        return c == '"' ? i + 1 : i;
      } else {
        i++;
      }
    }
    return content.length();
  }

  private static int newlines(final String content, final int from, final int to) {
    int count = 0;
    for (int i = from; i < to && i < content.length(); i++) {
      if (content.charAt(i) == '\n') {
        count++;
      }
    }
    return count;
  }
}
