package com.simonrowe.school;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Term Time, the school assistant.
 *
 * <p>Everything defaults to off or empty. The feature is inert until an operator sets
 * {@code school.enabled}, which is what lets this ship to production ahead of the credentials
 * being present.
 *
 * <p>{@code chatModel} and {@code guardrailModel} are read here and applied to the school
 * {@code ChatClient}'s own options — deliberately not set under {@code spring.ai.openai.chat},
 * whose values are merged into every per-call {@code OpenAiChatOptions} in the whole application.
 * That merge is why {@code reasoning-effort} is banned from the yml, and the same trap applies to
 * any model or cache key set globally.
 *
 * @param enabled master switch; nothing ingests, indexes or answers while false
 * @param ingestFromDate earliest source date to ingest, or null for the full available history
 * @param senderAllowlist mail sender <b>addresses</b> to ingest. Never matched against display
 *     names: a third-party system already sends mail displaying the school's own name, so a
 *     display-name allowlist admits anything willing to set one
 * @param senderDenylist addresses to exclude even if they would otherwise match
 * @param calendarBaseUrl base URL of the school calendar feed
 * @param websiteBaseUrl base URL of the school website
 * @param staffListUrl page listing published staff, the allowlist of names permitted in public
 *     content
 * @param crawlDelaySeconds politeness delay between website fetches; the site's robots.txt asks
 *     for ten
 * @param chatModel model for the school chat client
 * @param guardrailModel model for the topic gate and the output-side name check
 * @param dailyTokenBudget hard ceiling on tokens spent serving anonymous traffic per day; zero
 *     disables anonymous answering entirely rather than defaulting to unlimited
 * @param publicBaseUrl absolute origin that attachment links in an answer are built against.
 *     Must not be relative: a model handed {@code /api/school/attachments/<id>} has to supply an
 *     origin to write a markdown link, and it invents the school's own domain because that is
 *     what the rest of the answer cites — so every PDF link 404s
 */
@ConfigurationProperties("school")
public record SchoolProperties(
    boolean enabled,
    LocalDate ingestFromDate,
    List<String> senderAllowlist,
    List<String> senderDenylist,
    String calendarBaseUrl,
    String websiteBaseUrl,
    String staffListUrl,
    int crawlDelaySeconds,
    String chatModel,
    String guardrailModel,
    long dailyTokenBudget,
    String publicBaseUrl
) {

  private static final int DEFAULT_CRAWL_DELAY_SECONDS = 10;

  /** Applies defaults. Note that {@code enabled} is deliberately not defaulted to true. */
  public SchoolProperties {
    senderAllowlist = normalise(senderAllowlist);
    senderDenylist = normalise(senderDenylist);
    calendarBaseUrl = defaulted(calendarBaseUrl, "https://www.kilmorieschool.co.uk/calendar");
    websiteBaseUrl = defaulted(websiteBaseUrl, "https://www.kilmorieschool.co.uk");
    // Verified live 2026-09-08: /our-school/staff is a 404, /our-school/our-staff is the real
    // page. Getting this wrong is not loud — the directory simply stays empty, StaffNameGate
    // then treats every name as private, and most of the website lands RESTRICTED with nothing
    // in the logs but one WARN. Check this URL if the public tier looks unexpectedly thin.
    staffListUrl = defaulted(staffListUrl, websiteBaseUrl + "/our-school/our-staff");
    crawlDelaySeconds = crawlDelaySeconds <= 0 ? DEFAULT_CRAWL_DELAY_SECONDS : crawlDelaySeconds;
    chatModel = defaulted(chatModel, "gpt-5.6-luna");
    guardrailModel = defaulted(guardrailModel, "gpt-5-nano");
    publicBaseUrl = stripTrailingSlash(
        defaulted(publicBaseUrl, "https://term-time.simonrowe.dev"));
  }

  /**
   * Whether a sender address may be ingested.
   *
   * <p>Compares the bare address, lower-cased. Callers must extract the address from the
   * {@code From} header before calling — passing a whole header through would let
   * {@code "Kilmorie Primary School" <anything@example.com>} match on the display name, which is
   * exactly the shape of the real sender this guards against.
   *
   * @param address a bare email address, with no display name
   * @return true when the address is allowed and not denied
   */
  public boolean allowsSender(final String address) {
    if (address == null || address.isBlank()) {
      return false;
    }
    final String candidate = address.toLowerCase(Locale.ROOT).trim();
    if (senderDenylist.stream().anyMatch(candidate::endsWith)) {
      return false;
    }
    return senderAllowlist.stream().anyMatch(candidate::endsWith);
  }

  private static List<String> normalise(final List<String> values) {
    return values == null
        ? List.of()
        : values.stream().map(v -> v.toLowerCase(Locale.ROOT).trim()).filter(v -> !v.isEmpty())
            .toList();
  }

  private static String defaulted(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /**
   * Removes a trailing slash so callers can concatenate a rooted path without doubling it.
   *
   * @param value the configured URL
   * @return the same URL without a trailing slash
   */
  private static String stripTrailingSlash(final String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
