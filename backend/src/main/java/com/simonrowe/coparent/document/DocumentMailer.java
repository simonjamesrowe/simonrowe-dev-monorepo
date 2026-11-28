package com.simonrowe.coparent.document;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.DueKind;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Document emails: the batched "what changed" notice and the expiry and review reminders.
 * Best-effort and behind {@code coparent.email-enabled}, like {@code ExpenseMailer}: a failed
 * send is logged, never thrown, and nothing goes to a parent who is not {@code active}.
 */
@Component
public class DocumentMailer {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentMailer.class);
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

  private final JavaMailSender mailSender;
  private final CoparentProperties properties;

  public DocumentMailer(final JavaMailSender mailSender, final CoparentProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties;
  }

  /** One email for a batch of changes by {@code by} (null when more than one parent acted). */
  public void batch(final Parent to, final Parent by, final List<DocumentNoticeText.Line> lines) {
    final String who = by == null ? "Your co-parent" : firstName(by);
    final String list = lines.stream()
        .map(line -> "- %s: %s %s".formatted(capitalise(DocumentNoticeText.verb(line.change(),
            true)), line.title(), link(line.documentId())))
        .collect(Collectors.joining("\n"));
    send(to, DocumentNoticeText.summary(who, lines), """
        %s made changes to the family's documents:

        %s

        See them all: %s/documents
        """.formatted(who, list, properties.appUrl()));
  }

  /** A document is approaching its expiry or review date. */
  public void reminder(final Parent to, final FamilyDocument document, final String forWhom,
      final String inAbout) {
    final boolean expires = document.dueKind() == DueKind.EXPIRES;
    final String date = DAY.format(document.dueOn());
    final String subject = expires
        ? "%s%s expires on %s".formatted(document.title(), forWhom, date)
        : "%s%s is due for review on %s".formatted(document.title(), forWhom, date);
    send(to, subject, """
        %s%s %s on %s, %s.

        %s

        Open it: %s
        """.formatted(document.title(), forWhom,
        expires ? "expires" : "is due for review", date, inAbout,
        expires ? "Renewals can take a while, so it is worth starting now."
            : "When it has been reviewed, upload the new version so the next reminder follows it.",
        link(document.id())));
  }

  /** How far away a due date is, in words, for a reminder. */
  static String inAbout(final LocalDate today, final LocalDate dueOn,
      final ReminderWindows.Threshold threshold) {
    if (today.equals(dueOn)) {
      return "today";
    }
    return switch (threshold.label()) {
      case "6m" -> "in about six months";
      case "3m" -> "in about three months";
      case "1m" -> "in about a month";
      default -> "within a week";
    };
  }

  private void send(final Parent to, final String subject, final String body) {
    if (!properties.emailEnabled() || to.email() == null || to.email().isBlank()) {
      return;
    }
    // A co-parent who has not accepted their invitation has no account to open anything in.
    if (!CoparentAccessPolicy.ACTIVE.equals(to.status())) {
      return;
    }
    final SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(properties.emailFrom());
    message.setTo(to.email());
    message.setSubject(subject);
    message.setText(body);
    try {
      mailSender.send(message);
    } catch (MailException exception) {
      LOG.warn("Could not send document email: {}", exception.getMessage());
    }
  }

  private String link(final ObjectId documentId) {
    return properties.appUrl() + "/documents?doc=" + documentId.toHexString();
  }

  private static String capitalise(final String text) {
    return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  static String firstName(final Parent parent) {
    final String name = parent.fullName() == null ? "" : parent.fullName().trim();
    final int space = name.indexOf(' ');
    return name.isEmpty() ? "Your co-parent" : space < 0 ? name : name.substring(0, space);
  }
}
