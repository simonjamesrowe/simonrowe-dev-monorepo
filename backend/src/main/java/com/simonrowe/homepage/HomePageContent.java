package com.simonrowe.homepage;

import com.simonrowe.homepage.HomePage.AskPill;
import com.simonrowe.homepage.HomePage.Cta;
import java.time.Instant;

/**
 * The hero copy as the API reads and writes it: every editable field, so a save that sends
 * back what it loaded changes nothing. {@code updatedAt} is ignored on write and null until
 * the first save — that is how the editor tells the seeded defaults from edited copy.
 */
public record HomePageContent(
    String headlineLine1,
    String headlineLine2,
    String lede,
    Cta primaryCta,
    Cta secondaryCta,
    boolean showTourLink,
    String tourLinkLabel,
    AskPill askPill,
    Instant updatedAt
) {

  static HomePageContent from(final HomePage page) {
    return new HomePageContent(
        page.headlineLine1(),
        page.headlineLine2(),
        page.lede(),
        page.primaryCta(),
        page.secondaryCta(),
        page.showTourLink(),
        page.tourLinkLabel(),
        page.askPill(),
        page.updatedAt());
  }
}
