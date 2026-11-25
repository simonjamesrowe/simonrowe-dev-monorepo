package com.simonrowe.school.judge;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the publication judge, which decides school email into the public or the
 * private tier so the owner does not have to.
 *
 * @param mode {@code off}, {@code shadow} (record a verdict, decide nothing) or {@code auto}
 * @param model the model the judge asks
 * @param approveThreshold the confidence a PUBLIC verdict needs before it publishes anything
 * @param declineThreshold the confidence a PRIVATE verdict needs before it declines anything
 * @param broadcastSenders the addresses the school sends whole-group mail from. Mail from any
 *     other school address (attendance, accounts, wrap) has always been about one family
 * @param schoolDomain the domain a DMARC pass must be for, to count as proof of the sender
 * @param trustedEnvelopeSenders envelope senders, as {@code prefix*suffix} globs, that Google's
 *     SPF check proves came from the school's own mailing platform. See
 *     {@link SenderAuthentication} for why the school's broadcasts need this
 * @param householdNames names of this mailbox's family. Never committed: the repository is
 *     public. Empty is safe, the judge still reads every email, it only removes a backstop
 */
@ConfigurationProperties("school.judge")
public record SchoolJudgeProperties(
    Mode mode,
    String model,
    double approveThreshold,
    double declineThreshold,
    List<String> broadcastSenders,
    String schoolDomain,
    List<String> trustedEnvelopeSenders,
    List<String> householdNames
) {

  private static final double DEFAULT_THRESHOLD = 0.9;

  /** What the judge is allowed to do. */
  public enum Mode {
    /** Nothing is judged. */
    OFF,
    /** Verdicts are recorded and shown, and nothing is decided. */
    SHADOW,
    /** Verdicts above the thresholds decide the document. */
    AUTO
  }

  /** Applies defaults and normalises the lists. Off unless configured otherwise. */
  public SchoolJudgeProperties {
    mode = mode == null ? Mode.OFF : mode;
    model = model == null || model.isBlank() ? "gpt-6-luna" : model;
    approveThreshold = approveThreshold <= 0 ? DEFAULT_THRESHOLD : approveThreshold;
    declineThreshold = declineThreshold <= 0 ? DEFAULT_THRESHOLD : declineThreshold;
    broadcastSenders = lower(broadcastSenders);
    schoolDomain = schoolDomain == null ? "" : schoolDomain.trim().toLowerCase(Locale.ROOT);
    trustedEnvelopeSenders = lower(trustedEnvelopeSenders);
    householdNames = householdNames == null ? List.of() : householdNames.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .distinct()
        .toList();
  }

  private static List<String> lower(final List<String> values) {
    return values == null ? List.of() : values.stream()
        .filter(Objects::nonNull)
        .map(value -> value.trim().toLowerCase(Locale.ROOT))
        .filter(value -> !value.isEmpty())
        .distinct()
        .toList();
  }
}
