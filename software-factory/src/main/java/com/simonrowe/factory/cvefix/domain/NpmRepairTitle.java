package com.simonrowe.factory.cvefix.domain;

import com.simonrowe.factory.git.NpmLockPins;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The title of an npm repair ticket, which is also how the ticket agent recognises one.
 *
 * <p>The scan writes it and the ticket agent reads it, so both sides use this class and the format
 * cannot drift. The agent reads only the package name and the fixed version, both pattern-checked:
 * nothing else in a ticket reaches the repair workflow.
 */
public final class NpmRepairTitle {

  private static final String PREFIX = "Bump npm package ";
  private static final int MAX_TITLE_IDS = 2;

  /** A release version: three numbers, no pre-release or build part. */
  public static final Pattern RELEASE = Pattern.compile("[0-9]{1,9}+\\.[0-9]{1,9}+\\.[0-9]{1,9}+");

  private static final Pattern TITLE =
      Pattern.compile(
          Pattern.quote(PREFIX) + "(%s) to (%s) \\([A-Za-z0-9 ,-]{1,120}+\\)"
              .formatted(NpmLockPins.NAME.pattern(), RELEASE.pattern()));

  private NpmRepairTitle() {
  }

  /**
   * A package and the release that fixes it.
   *
   * @param name the npm package name
   * @param fixedVersion the lowest release fixing every advisory
   */
  public record Parsed(String name, String fixedVersion) {
  }

  /**
   * The title for a repair.
   *
   * @param name the package
   * @param fixedVersion the release to move to
   * @param advisoryIds the advisories it fixes
   * @return the title
   */
  public static String format(
      final String name, final String fixedVersion, final List<String> advisoryIds) {
    String advisories = advisoryIds.size() <= MAX_TITLE_IDS
        ? String.join(", ", advisoryIds)
        : advisoryIds.size() + " advisories";
    return PREFIX + name + " to " + fixedVersion + " (" + advisories + ")";
  }

  /**
   * Reads a title written by {@link #format}.
   *
   * @param title a ticket title
   * @return the package and version, or empty when the title is not an npm repair's
   */
  public static Optional<Parsed> parse(final String title) {
    if (title == null || title.length() > 400) {
      return Optional.empty();
    }
    Matcher matcher = TITLE.matcher(title.strip());
    return matcher.matches()
        ? Optional.of(new Parsed(matcher.group(1), matcher.group(2)))
        : Optional.empty();
  }
}
