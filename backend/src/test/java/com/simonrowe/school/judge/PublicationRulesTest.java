package com.simonrowe.school.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The fixed rules. Every fixture is synthetic, in the school's layout: real school mail is never
 * committed, because the repository is public.
 */
class PublicationRulesTest {

  private static final String BROADCAST = "info@school.example";
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
  private static final Instant THIS_TERM = Instant.parse("2026-11-20T15:00:00Z");
  private static final String SIGNATURE = """

      Kind regards,
      Office Team

      Example Primary School
      Example Road London SE1 1AA
      t: 020 0000 0000
      w: https://school.example
      """;

  private final PublicationRules rules = new PublicationRules(properties(List.of("Pat", "Lee")));

  private static SchoolJudgeProperties properties(final List<String> household) {
    return new SchoolJudgeProperties(SchoolJudgeProperties.Mode.AUTO, null, 0, 0,
        List.of(BROADCAST), "school.example", List.of(), household);
  }

  private Optional<PublicationRules.Hit> email(final String from, final String subject,
      final String body) {
    return rules.email(from, subject, body, THIS_TERM, TODAY);
  }

  @Test
  void broadcastWithStaffNamesPhoneNumberAndFormLinkPassesEveryRule() {
    final String body = """
        Dear Parents and Carers,

        Year 3 Football Club bookings are now open on Arbor. Please complete the consent form \
        by Friday. Contact Ms Hart with any questions.
        """ + SIGNATURE;

    assertThat(email(BROADCAST, "Year 3 Football Club", body)).isEmpty();
  }

  @Test
  void theReportingSystemIsNeverPublishedEvenWithNoBroadcastSenderList() {
    // Its "A report for <child> is ready" emails name one child. They are ingested only for
    // CoParent's report prompts, so the rule must hold even if the sender list is emptied.
    final PublicationRules open = new PublicationRules(new SchoolJudgeProperties(
        SchoolJudgeProperties.Mode.AUTO, null, 0, 0, List.of(), "school.example", List.of(),
        List.of()));
    for (final String from : List.of("system@insighttracking.com",
        "SYSTEM@InsightTracking.com ", "noreply@mail.insighttracking.com")) {
      assertThat(open.email(from, "A report for Robin is ready", "Log in to read it.",
          THIS_TERM, TODAY)).get().extracting(PublicationRules.Hit::code)
          .isEqualTo("reporting-system");
    }
    assertThat(rules.email("system@insighttracking.com", "A report for Robin is ready",
        "Log in.", THIS_TERM, TODAY)).get().extracting(PublicationRules.Hit::code)
        .isEqualTo("reporting-system");
    assertThat(open.email("info@notinsighttracking.com", "Hello", "Hi.", THIS_TERM, TODAY))
        .as("a look-alike domain is not the reporting system").isEmpty();
    assertThat(open.email(BROADCAST, "Year 3 trip", "Dear parents.", THIS_TERM, TODAY))
        .isEmpty();
  }

  @Test
  void mailFromAnyOtherSchoolAddressIsAboutOneFamily() {
    assertThat(email("attendance@school.example", "Absence", "Thank you." + SIGNATURE))
        .map(PublicationRules.Hit::code).contains("sender");
  }

  @ParameterizedTest
  @ValueSource(strings = {"RE: collection today", "FW: reports", "Fwd: reports",
      "Automatic reply: collection today", "re : pickup"})
  void repliesForwardsAndAutoRepliesArePrivate(final String subject) {
    assertThat(email(BROADCAST, subject, "Thank you for your email." + SIGNATURE))
        .map(PublicationRules.Hit::code).contains("reply");
  }

  @Test
  void quotedMessageIsPrivateEvenWithoutReplySubject() {
    final String body = """
        Please see attached as requested.

        From: A Parent <parent@example.com>
        Sent: 18 September 2026 16:50
        To: Office <office@school.example>
        Subject: Request to re-send reports
        """;

    assertThat(email(BROADCAST, "Reports", body))
        .map(PublicationRules.Hit::code).contains("quoted-message");
  }

  @Test
  void quotedMessageWithWindowsLineEndingsIsStillFound() {
    final String body = "Thanks.\r\n\r\nFrom: A Parent <p@example.com>\r\nSent: today\r\n";

    assertThat(email(BROADCAST, "Thanks", body))
        .map(PublicationRules.Hit::code).contains("quoted-message");
  }

  @Test
  void fromLineInNewsletterIsNotQuotedMessage() {
    final String body = """
        Dear Parents and Carers,

        From: Monday the hall is closed for maintenance.
        Our Christmas fair raised a record amount.
        """ + SIGNATURE;

    assertThat(email(BROADCAST, "Weekly Newsletter", body)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "This is an automated email to confirm that a Top-Up of £60.00 has been added.",
      "There is an outstanding balance on the account. Please pay by 30 November.",
      "This message is confirmation for an event booked. Meeting Booking Confirmation.",
      "Please see the payment history below."})
  void paymentBalanceAndBookingNoticesArePrivate(final String sentence) {
    assertThat(email(BROADCAST, "Notice", sentence + SIGNATURE))
        .map(PublicationRules.Hit::code).contains("transactional");
  }

  @Test
  void paymentSuccessfulSubjectIsPrivateOnItsOwn() {
    assertThat(email(BROADCAST, "Your payment to Example Primary School was successful",
        "All the best."))
        .map(PublicationRules.Hit::code).contains("transactional");
  }

  @Test
  void namingSomeoneInTheHouseholdIsPrivate() {
    final String body = "Dear Parents,\n\nPat will be collected by a childminder today.";

    assertThat(email(BROADCAST, "Collection", body))
        .map(PublicationRules.Hit::code).contains("household");
  }

  @Test
  void mailMergeGreetingNamingTheReaderIsNotEvidence() {
    // The owner approved broadcasts that open "Good afternoon <name> ,".
    final String body = """
        Good afternoon Lee ,

        Thank you to all parents and carers who have already completed the sibling discount \
        form. Please complete it by Friday.
        """ + SIGNATURE;

    assertThat(email(BROADCAST, "Sibling discount reminder", body)).isEmpty();
  }

  @Test
  void householdNamesMatchWholeWordsOnly() {
    // "Pat" must not match "Patterns", nor "Lee" match "Leeds".
    final String body = "Patterns in maths this week, and a trip to Leeds." + SIGNATURE;

    assertThat(email(BROADCAST, "Curriculum", body)).isEmpty();
  }

  @Test
  void withNoHouseholdConfiguredTheNameRuleIsOffNotBroken() {
    final PublicationRules unconfigured = new PublicationRules(properties(List.of()));

    assertThat(unconfigured.email(BROADCAST, "Collection", "Pat will be collected.",
        THIS_TERM, TODAY)).isEmpty();
  }

  @Test
  void lastSchoolYearsMailIsPrivate() {
    final Instant july = Instant.parse("2026-07-10T12:00:00Z");

    assertThat(rules.email(BROADCAST, "Weekly Newsletter 10th July", "News." + SIGNATURE,
        july, TODAY))
        .map(PublicationRules.Hit::reason).contains("From the 2025/26 school year");
  }

  @Test
  void mailFromTheFirstDayOfThisSchoolYearIsCurrent() {
    final Instant firstOfSeptember = Instant.parse("2026-09-01T07:00:00Z");

    assertThat(rules.email(BROADCAST, "Welcome back", "Welcome." + SIGNATURE,
        firstOfSeptember, TODAY)).isEmpty();
  }

  @Test
  void augustMailAboutTheNewTermIsNotStaleInSeptember() {
    final Instant lateAugust = Instant.parse("2026-08-27T10:00:00Z");

    assertThat(rules.email(BROADCAST, "New school lunch menu", "Menu." + SIGNATURE,
        lateAugust, LocalDate.of(2026, 9, 2))).isEmpty();
  }

  @Test
  void attachmentNamedAfterPupilIsPrivate() {
    assertThat(rules.attachment("Smith, Sam .pdf", "End of year report", THIS_TERM, TODAY))
        .map(PublicationRules.Hit::code).contains("pupil-document");
  }

  @Test
  void anOrdinaryLetterAttachmentPassesTheAttachmentRules() {
    assertThat(rules.attachment("Year 3 Local area walk trip letter.pdf",
        "Dear Parents, Year 3 will walk to the park on Tuesday.", THIS_TERM, TODAY)).isEmpty();
  }

  @Test
  void anAttachmentNamingTheHouseholdIsPrivate() {
    assertThat(rules.attachment("Report.pdf", "Lee has made good progress.", THIS_TERM, TODAY))
        .map(PublicationRules.Hit::code).contains("household");
  }

  @Test
  void everyRuleFinishesQuicklyOnHostileInput() {
    // Shaped to tempt backtracking: one enormous line, a "From:" line that never closes, and
    // a greeting-shaped line that runs on. Possessive quantifiers keep each match linear.
    final String hostile = String.join("\n", "From:" + "x".repeat(50_000),
        "Dear " + "a ".repeat(30_000), "payment to " + "y".repeat(40_000));
    final long started = System.nanoTime();

    rules.email(BROADCAST, "re" + " ".repeat(100_000), hostile, THIS_TERM, TODAY);
    rules.attachment("A".repeat(100_000) + ", B.pdf", hostile, THIS_TERM, TODAY);

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }
}
