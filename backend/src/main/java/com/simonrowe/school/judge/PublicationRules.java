package com.simonrowe.school.judge;

import com.simonrowe.school.model.AcademicYear;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The fixed rules that keep an email private before any model sees it.
 *
 * <p>Each rule was read off the owner's own decisions (130 emails, 2026-07 to 2026-11) and none
 * of them fired on a single email the owner approved. They only ever decline: a rule that has not
 * fired says nothing, and the model still decides. That asymmetry is the point. A rule is
 * predictable and cannot be talked out of its answer by the text it is reading, so it is the
 * right tool for "this is plainly about one family", and the wrong one for "this is fine".
 *
 * <p>Every pattern runs over untrusted text of unbounded length, so each one uses possessive or
 * bounded quantifiers and is tested against a 100k-character body.
 */
public final class PublicationRules {

  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final int STALE_AFTER_DAYS = 30;

  private static final Pattern REPLY_SUBJECT = Pattern.compile(
      "^\\s*+(?:re|fw|fwd|automatic reply|auto-reply|out of office)\\s*+:",
      Pattern.CASE_INSENSITIVE);

  /**
   * A quoted message header block, the shape Outlook leaves when the school replies to or
   * forwards something a parent sent ({@code From:} then {@code Sent:} on the next line). A
   * broadcast never quotes anyone.
   */
  private static final Pattern QUOTED_MESSAGE = Pattern.compile(
      "^From:[^\\n]{0,300}+\\n(?:Sent|Date):", Pattern.MULTILINE);

  private static final Pattern TRANSACTIONAL = Pattern.compile(String.join("|",
      "payment to [^\\n]{0,80}? was successful", "outstanding balance", "booking confirmation",
      "payment history", "a top-up of"), Pattern.CASE_INSENSITIVE);

  /**
   * A greeting line. Arbor mail-merges the recipient's first name into some broadcasts ("Good
   * afternoon Simon ,"), and the owner approved those, so a name in a greeting is not evidence.
   */
  private static final Pattern GREETING_LINE = Pattern.compile(
      "^[ \\t]*+(?:dear|hi|hello|good (?:morning|afternoon|evening))\\b[^\\n]{0,60}+$",
      Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

  /** A report or other per-pupil document named "Surname, Forename.pdf". */
  private static final Pattern PUPIL_FILENAME = Pattern.compile(
      "^[A-Z][A-Za-z'-]{1,40}+, ?+[A-Z][A-Za-z'-]{1,40}+ ?+\\.pdf$");

  /**
   * The school's reporting system. Every email it sends ("A report for Mia is ready") names one
   * child and is read only so CoParent can prompt that child's family, so none may ever be
   * published, whatever the broadcast-sender list says.
   */
  static final String REPORTING_SYSTEM_DOMAIN = "insighttracking.com";

  /** Why a rule fired. {@code code} is stable and shown on the admin page. */
  public record Hit(String code, String reason) {
  }

  private final List<String> broadcastSenders;
  private final Pattern householdNames;

  /**
   * Builds the rules from configuration.
   *
   * @param properties the judge's configuration
   */
  public PublicationRules(final SchoolJudgeProperties properties) {
    this.broadcastSenders = properties.broadcastSenders();
    // Quoted, never interpreted: these come from configuration and are names, not patterns.
    this.householdNames = properties.householdNames().isEmpty()
        ? null
        : Pattern.compile(properties.householdNames().stream()
            .map(Pattern::quote)
            .collect(Collectors.joining("|", "\\b(?:", ")\\b")));
  }

  /**
   * The first rule an email breaks, if any.
   *
   * @param fromAddress the bare sender address
   * @param subject the subject line
   * @param body the plain-text body
   * @param publishedAt when it was sent
   * @param today the current date
   * @return the rule that keeps it private, or empty when none applies
   */
  public Optional<Hit> email(final String fromAddress, final String subject, final String body,
      final Instant publishedAt, final LocalDate today) {
    final String text = normalise(body);
    final String from = fromAddress == null ? "" : fromAddress.toLowerCase(Locale.ROOT).trim();
    if (fromReportingSystem(from)) {
      return Optional.of(new Hit("reporting-system",
          "From the school's reporting system, about one child"));
    }
    if (!broadcastSenders.isEmpty() && !broadcastSenders.contains(from)) {
      return Optional.of(new Hit("sender", "Sent from %s, not the school's broadcast address"
          .formatted(from.isEmpty() ? "an unknown address" : from)));
    }
    if (subject != null && REPLY_SUBJECT.matcher(subject).find()) {
      return Optional.of(new Hit("reply", "A reply, forward or auto-reply"));
    }
    if (QUOTED_MESSAGE.matcher(text).find()) {
      return Optional.of(new Hit("quoted-message", "Quotes an email someone sent the school"));
    }
    if (TRANSACTIONAL.matcher(text).find()
        || (subject != null && TRANSACTIONAL.matcher(subject).find())) {
      return Optional.of(new Hit("transactional",
          "A payment, balance or booking notice for one family"));
    }
    return household(text).or(() -> previousYear(publishedAt, today));
  }

  /**
   * The first rule an email attachment breaks, if any. An attachment otherwise follows its
   * email, so these only ever make it more private than the email that carried it.
   *
   * @param filename the attachment's file name
   * @param body its extracted text
   * @param publishedAt when its email was sent
   * @param today the current date
   * @return the rule that keeps it private, or empty when none applies
   */
  public Optional<Hit> attachment(final String filename, final String body,
      final Instant publishedAt, final LocalDate today) {
    if (filename != null && PUPIL_FILENAME.matcher(filename.trim()).matches()) {
      return Optional.of(new Hit("pupil-document", "Named after one pupil"));
    }
    return household(normalise(body)).or(() -> previousYear(publishedAt, today));
  }

  /** The reporting system's domain or a subdomain of it, compared on the parsed domain. */
  private static boolean fromReportingSystem(final String from) {
    final int at = from.lastIndexOf('@');
    if (at < 0) {
      return false;
    }
    final String domain = from.substring(at + 1);
    return domain.equals(REPORTING_SYSTEM_DOMAIN)
        || domain.endsWith("." + REPORTING_SYSTEM_DOMAIN);
  }

  private Optional<Hit> household(final String text) {
    if (householdNames == null) {
      return Optional.empty();
    }
    final String withoutGreetings = GREETING_LINE.matcher(text).replaceAll("");
    return householdNames.matcher(withoutGreetings).find()
        ? Optional.of(new Hit("household", "Names someone in this family"))
        : Optional.empty();
  }

  /**
   * Last school year's news. The owner declined July's broadcasts once September started, and
   * answered "the judge should decline" when asked whether stale mail should be kept out.
   *
   * <p>Also at least {@link #STALE_AFTER_DAYS} days old, because August mail is mostly about
   * September: the new term's menu and club lists arrive in the last week of the holidays, and
   * a sweep running on 2 September must not decline them for being from "last year".
   */
  private static Optional<Hit> previousYear(final Instant publishedAt, final LocalDate today) {
    if (publishedAt == null) {
      return Optional.empty();
    }
    final LocalDate sentOn = publishedAt.atZone(LONDON).toLocalDate();
    final String sent = AcademicYear.of(sentOn);
    final boolean earlierYear = sent.compareTo(AcademicYear.current(today)) < 0;
    return earlierYear && sentOn.isBefore(today.minusDays(STALE_AFTER_DAYS))
        ? Optional.of(new Hit("previous-year", "From the " + sent + " school year"))
        : Optional.empty();
  }

  private static String normalise(final String body) {
    return body == null ? "" : body.replace("\r\n", "\n").replace('\r', '\n');
  }
}
