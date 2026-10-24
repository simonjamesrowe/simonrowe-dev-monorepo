package com.simonrowe.factory.ticketagent.domain;

import java.util.regex.Pattern;

/**
 * Makes the agent's pull request title a conventional-commit title, whatever the model wrote.
 *
 * <p>The title becomes the squash commit on {@code main}, and this repository's history is
 * conventional commits throughout. The agent is asked for one, but asking is not a guarantee: on
 * 2026-10-24 it titled #287 "Bump plexus-utils to 3.6.1 (…)". So a title without a type is given
 * {@code fix:}, the right type for almost every ticket the agent takes (log errors, vulnerability
 * repairs, quality findings), rather than being trusted to the prompt.
 */
public final class ConventionalTitle {

  /** A type, an optional scope and breaking marker, a colon and a space, then the subject. */
  private static final Pattern CONVENTIONAL =
      Pattern.compile(
          "(?:%s)(?:\\([A-Za-z0-9._/-]{1,40}+\\))?+!?+: \\S.*+"
              .formatted("feat|fix|chore|docs|refactor|test|perf|build|ci|style|revert"),
          Pattern.DOTALL);

  private static final String DEFAULT_TYPE = "fix: ";

  private ConventionalTitle() {
  }

  /**
   * The title with a conventional type.
   *
   * @param title what the agent wrote; may be null
   * @return the title unchanged when it already has a type, {@code fix: } plus the title
   *     otherwise (its first word lower-cased unless it is an acronym), or null when blank
   */
  public static String of(final String title) {
    if (title == null || title.isBlank()) {
      return null;
    }
    String trimmed = title.strip();
    if (CONVENTIONAL.matcher(trimmed).matches()) {
      return trimmed;
    }
    boolean capitalisedWord = trimmed.length() > 1
        && Character.isUpperCase(trimmed.charAt(0)) && Character.isLowerCase(trimmed.charAt(1));
    String subject = capitalisedWord
        ? Character.toLowerCase(trimmed.charAt(0)) + trimmed.substring(1)
        : trimmed;
    return DEFAULT_TYPE + subject;
  }
}
