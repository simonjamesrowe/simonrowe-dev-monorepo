package com.simonrowe.homepage;

import com.simonrowe.homepage.HomePage.AskPill;
import com.simonrowe.homepage.HomePage.Cta;

/**
 * The hero copy shown until an administrator first saves some.
 *
 * <p>Served rather than seeded: no change unit writes this, so a fresh environment, and a
 * restore from a backup taken before the collection existed, both show a complete hero with
 * no data migration. The first save from the editor replaces it for good.
 */
final class HomePageDefaults {

  static final HomePageContent CONTENT = new HomePageContent(
      "Leading engineering teams.",
      "Building AI-native systems.",
      "Lately I've been building a software factory: autonomous agents that review pull "
          + "requests, patch CVEs, deploy to production and file their own bugs.",
      new Cta("See my experience", "/about#roles"),
      new Cta("Get in touch", "/about#contact"),
      true,
      "Take a tour",
      new AskPill("Got a question?", "Ask Simon anything", "Start chat"),
      null);

  private HomePageDefaults() {
  }
}
