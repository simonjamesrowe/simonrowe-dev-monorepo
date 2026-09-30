package com.simonrowe.homepage;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The home page's editable hero copy: one document, always {@link #SINGLETON_ID}.
 *
 * <p>Presentation only. The name, title, location and background images the hero also shows
 * belong to the profile, which is identity data served to the CV, chat and MCP as well, and
 * is edited on its own page. Keeping the copy out of it leaves that record's round-trip
 * surface unchanged.
 */
@Document(collection = HomePage.COLLECTION)
public record HomePage(
    @Id String id,
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

  public static final String COLLECTION = "home_page";
  public static final String SINGLETON_ID = "home";

  /** A call to action: a label and where it goes (a site path or an {@code https} URL). */
  public record Cta(String label, String href) {
  }

  /** The "Ask Simon anything" pill: optional lead-in text, its label and its button. */
  public record AskPill(String lead, String label, String buttonLabel) {
  }
}
