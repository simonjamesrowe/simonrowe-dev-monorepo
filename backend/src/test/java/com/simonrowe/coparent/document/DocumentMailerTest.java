package com.simonrowe.coparent.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.DocumentNotice.Change;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.coparent.model.DocumentSource;
import com.simonrowe.coparent.model.DueKind;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.model.Parent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class DocumentMailerTest {

  private static final Parent ALEX = parent("Alex Morgan", "alex@example.com", "active");
  private static final Parent SAM = parent("Sam Taylor", "sam@example.com", "active");

  private final JavaMailSender sender = mock(JavaMailSender.class);

  @Test
  void batchNamesWhoDidWhatWithLinkToEachDocument() {
    final ObjectId passport = new ObjectId();
    final ObjectId letter = new ObjectId();

    mailer(true).batch(SAM, ALEX, List.of(
        new DocumentNoticeText.Line(Change.ADDED, "Passport", passport),
        new DocumentNoticeText.Line(Change.ARCHIVED, "Trip letter", letter)));

    final SimpleMailMessage message = sent();
    assertThat(message.getTo()).containsExactly("sam@example.com");
    assertThat(message.getSubject()).isEqualTo("Alex added 1 document and archived 1");
    assertThat(message.getText()).contains(
        "- Added: Passport https://coparents.example/documents?doc=" + passport.toHexString(),
        "- Archived: Trip letter https://coparents.example/documents?doc=%s"
            .formatted(letter.toHexString()));
  }

  @Test
  void reminderSaysWhenItExpires() {
    mailer(true).reminder(SAM, document(DueKind.EXPIRES), " (Leo)", "in about six months");

    final SimpleMailMessage message = sent();
    assertThat(message.getSubject()).isEqualTo("Passport (Leo) expires on 2 August 2027");
    assertThat(message.getText()).contains("in about six months", "/documents?doc=");
  }

  @Test
  void reviewReminderSaysReview() {
    mailer(true).reminder(SAM, document(DueKind.REVIEW), "", "within a week");

    assertThat(sent().getSubject()).isEqualTo("Passport is due for review on 2 August 2027");
  }

  @Test
  void nothingGoesWhenEmailIsOffOrTheParentHasNotJoined() {
    mailer(false).batch(SAM, ALEX, List.of(
        new DocumentNoticeText.Line(Change.ADDED, "Passport", new ObjectId())));
    mailer(true).reminder(parent("Rhian", "r@example.com", "invited"),
        document(DueKind.EXPIRES), "", "today");

    verify(sender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void failedSendIsLoggedNotThrown() {
    doThrow(new MailSendException("down")).when(sender).send(any(SimpleMailMessage.class));

    mailer(true).reminder(SAM, document(DueKind.EXPIRES), "", "today");
  }

  @Test
  void describesHowFarAwayTheDateIs() {
    final LocalDate due = LocalDate.of(2027, 8, 2);
    assertThat(DocumentMailer.inAbout(due, due, ReminderWindows.REVIEW.getLast()))
        .isEqualTo("today");
    assertThat(DocumentMailer.inAbout(due.minusMonths(3), due, ReminderWindows.EXPIRES.getLast()))
        .isEqualTo("in about three months");
    assertThat(DocumentMailer.inAbout(due.minusMonths(1), due, ReminderWindows.REVIEW.getFirst()))
        .isEqualTo("in about a month");
  }

  private DocumentMailer mailer(final boolean enabled) {
    return new DocumentMailer(sender, new CoparentProperties(true, "coparent",
        "https://coparents.example", "email", "noreply@example.com", enabled, "legacy",
        new CoparentProperties.Assistant(false, "model")));
  }

  private SimpleMailMessage sent() {
    final ArgumentCaptor<SimpleMailMessage> captor =
        ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender).send(captor.capture());
    return captor.getValue();
  }

  private static FamilyDocument document(final DueKind kind) {
    final Instant now = Instant.parse("2026-11-28T10:00:00Z");
    return new FamilyDocument(new ObjectId(), new ObjectId(), List.of(),
        DocumentSection.IDENTITY, "Passport", LocalDate.of(2017, 8, 2),
        LocalDate.of(2027, 8, 2), kind, null, List.of(), DocumentSource.upload(), null, null,
        Set.of(), new ObjectId(), now, now, 1);
  }

  private static Parent parent(final String name, final String email, final String status) {
    return new Parent(new ObjectId(), "auth0|x", new ObjectId(), name, email, "co-parent",
        status, null, null, null, null, null);
  }
}
