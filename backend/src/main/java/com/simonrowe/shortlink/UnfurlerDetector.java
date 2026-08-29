package com.simonrowe.shortlink;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Recognises the link-preview services that fetch a share link to build an unfurl card.
 *
 * <p>This exists because {@link ShortLinkController} serves the same Open Graph document
 * to <em>every</em> client rather than sniffing user agents to decide what to return. That
 * choice is right for the response — a missed bot there would silently break unfurling —
 * but it means one paste into a Slack channel fetches the link once before any human
 * clicks it, and LinkedIn, WhatsApp and iMessage all do the same. Without this filter most
 * of the click count would be robots reading metadata.
 *
 * <p>The cost of the two mistakes is deliberately asymmetric. Missing a bot inflates a
 * statistic; wrongly classifying a person as one loses a real click. Neither breaks a
 * link, which is why user-agent matching is acceptable here and was rejected for choosing
 * the response.
 */
@Component
public class UnfurlerDetector {

  /**
   * The specific agents worth naming.
   *
   * <p>{@link #GENERIC} already matches most of these. They are kept anyway because the
   * artefact with lasting value is {@code UnfurlerDetectorTest}, which pins the real
   * strings — when a platform changes its agent, that is the file that tells you what it
   * used to send.
   */
  private static final List<String> KNOWN_AGENTS = List.of(
      "slackbot",
      "facebookexternalhit",
      "linkedinbot",
      "whatsapp",
      "twitterbot",
      "discordbot",
      "telegrambot",
      "redditbot");

  /** Catches self-identifying robots that are not in the named list. */
  private static final Pattern GENERIC =
      Pattern.compile("bot|crawler|spider|preview", Pattern.CASE_INSENSITIVE);

  /**
   * Whether a request came from a link-preview service rather than a person.
   *
   * <p>A null or blank user agent counts as a person. An unidentified client is more
   * likely a stripped-down browser or a privacy tool than a robot, and the stated cost of
   * guessing wrong in that direction is only an inflated number.
   *
   * @param userAgent the request's {@code User-Agent} header, possibly null
   * @return true when the click should not be counted
   */
  public boolean isUnfurler(final String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return false;
    }
    String lowered = userAgent.toLowerCase(Locale.ROOT);
    for (String agent : KNOWN_AGENTS) {
      if (lowered.contains(agent)) {
        return true;
      }
    }
    return GENERIC.matcher(lowered).find();
  }
}
