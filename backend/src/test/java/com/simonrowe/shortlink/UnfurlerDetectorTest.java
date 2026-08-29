package com.simonrowe.shortlink;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the real user-agent strings each platform sends.
 *
 * <p>That is the point of this file: the detector's generic {@code bot|crawler|spider}
 * pattern already matches most of these, so the assertions are not what carries the value —
 * the recorded strings are. When a platform changes its agent and a link stops being
 * counted correctly, this is where you find out what it used to send.
 */
class UnfurlerDetectorTest {

  private final UnfurlerDetector detector = new UnfurlerDetector();

  @ParameterizedTest
  @ValueSource(strings = {
      // Slack, as of 2026. It sends two different agents: one when the link is posted,
      // one when a user expands it.
      "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)",
      "Slackbot 1.0 (+https://api.slack.com/robots)",
      // Facebook and Instagram share this one. iMessage's preview fetcher also presents
      // as facebookexternalhit on some paths.
      "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
      "facebookexternalhit/1.1",
      // LinkedIn.
      "LinkedInBot/1.0 (compatible; Mozilla/5.0; Apache-HttpClient +http://www.linkedin.com)",
      // WhatsApp — note it carries no "bot", so the generic pattern would miss it and the
      // named entry is doing real work.
      "WhatsApp/2.23.20.0 A",
      "WhatsApp/2.19.81 A",
      // X / Twitter.
      "Twitterbot/1.0",
      // Discord.
      "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)",
      // Telegram.
      "TelegramBot (like TwitterBot)",
      // Reddit.
      "redditbot/1.0 (+http://www.reddit.com/feedback)",
      // Not named individually, caught by the generic pattern.
      "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
      "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
      "Applebot/0.1; +http://www.apple.com/go/applebot",
      "Mozilla/5.0 (compatible; SomeCrawler/1.0)",
      "iframely/1.3.1 (+link-preview)",
  })
  void recognisesLinkPreviewServices(final String userAgent) {
    assertThat(detector.isUnfurler(userAgent))
        .as("should not count a click for: %s", userAgent)
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      // Safari on macOS.
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like "
          + "Gecko) Version/17.0 Safari/605.1.15",
      // Chrome on Windows.
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/128.0.0.0 Safari/537.36",
      // Firefox on Linux.
      "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0",
      // Safari on iPhone — the case that matters most, since the native share sheet is a
      // mobile feature and its recipients open links on phones.
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
      // Chrome on Android.
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/128.0.0.0 Mobile Safari/537.36",
      // Someone checking the link by hand.
      "curl/8.7.1",
      "Wget/1.21.4",
  })
  void doesNotMistakePeopleForRobots(final String userAgent) {
    assertThat(detector.isUnfurler(userAgent))
        .as("should count a click for: %s", userAgent)
        .isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void treatsAnUnidentifiedClientAsHuman(final String userAgent) {
    // A missing agent is more likely a stripped-down browser or a privacy tool than a
    // robot, and the cost of guessing wrong here is only a slightly inflated statistic.
    assertThat(detector.isUnfurler(userAgent)).isFalse();
  }

  @Test
  void matchesRegardlessOfCase() {
    assertThat(detector.isUnfurler("SLACKBOT-LINKEXPANDING 1.0")).isTrue();
    assertThat(detector.isUnfurler("whatsapp/2.23.20.0 a")).isTrue();
  }
}
