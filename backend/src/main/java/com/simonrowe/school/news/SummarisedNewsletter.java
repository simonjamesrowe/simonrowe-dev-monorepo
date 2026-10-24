package com.simonrowe.school.news;

import java.util.List;

/**
 * What the model returns for one newsletter, before it is checked.
 *
 * <p>A separate type from {@link NewsletterSummary}: this is untrusted model output, and
 * {@link SchoolNewsletterSummariser} trims, caps and sanitises it before anything is stored.
 *
 * @param headline a few words on what the week was about
 * @param points the things a parent needs to know
 */
public record SummarisedNewsletter(String headline, List<Point> points) {

  /** Normalises a null list so callers never null-check it. */
  public SummarisedNewsletter {
    points = points == null ? List.of() : List.copyOf(points);
  }

  /**
   * One point.
   *
   * @param text one or two sentences
   * @param yearGroups year groups such as {@code Year 3} or {@code Reception}, empty for all
   */
  public record Point(String text, List<String> yearGroups) {

    /** Normalises a null list, which the model omits for whole-school points. */
    public Point {
      yearGroups = yearGroups == null ? List.of() : List.copyOf(yearGroups);
    }
  }
}
