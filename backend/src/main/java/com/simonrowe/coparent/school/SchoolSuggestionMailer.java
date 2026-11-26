package com.simonrowe.coparent.school;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * One email per sync to each parent of a family with school events to look at. Best-effort: a
 * failed send is logged, never thrown, so mail can never stop a sync or undo what it saved, and
 * whatever was not mailed is still waiting in the list.
 */
@Component
public class SchoolSuggestionMailer {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolSuggestionMailer.class);
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK);

  private final JavaMailSender mailSender;
  private final CoparentProperties properties;

  public SchoolSuggestionMailer(final JavaMailSender mailSender,
      final CoparentProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties;
  }

  /**
   * Tells every active parent what needs a look.
   *
   * @param parents the family's active parents
   * @param items the suggestions this sync added or flagged, at least one
   * @param childNames first names by child id
   */
  public void needsApproval(final List<Parent> parents, final List<SchoolEventSuggestion> items,
      final Map<ObjectId, String> childNames) {
    if (items.isEmpty()) {
      return;
    }
    final String subject = items.size() == 1
        ? "A school event needs a look: " + items.getFirst().details().title()
        : items.size() + " school events need a look";
    final String body = """
        Term Time found %s for the family calendar. Either of you can add or dismiss \
        %s:

        %s

        Review: %s/calendar?school=1
        """.formatted(items.size() == 1 ? "a school event" : items.size() + " school events",
        items.size() == 1 ? "it" : "each one",
        items.stream().map(item -> line(item, childNames)).collect(Collectors.joining("\n")),
        properties.appUrl());
    parents.forEach(parent -> send(parent, subject, body));
  }

  static String line(final SchoolEventSuggestion item, final Map<ObjectId, String> childNames) {
    final SchoolEventSuggestion.Details shown =
        item.review() == SchoolEventSuggestion.Review.CHANGED && item.proposed() != null
            ? item.proposed() : item.details();
    final String children = item.childIds().stream().map(childNames::get)
        .filter(name -> name != null && !name.isBlank()).collect(Collectors.joining(", "));
    final String prefix = item.review() == null ? ""
        : item.review() == SchoolEventSuggestion.Review.CHANGED ? "Changed: "
        : "No longer on the school calendar: ";
    return "- %s%s, %s: %s%s".formatted(prefix, when(shown), time(shown), shown.title(),
        children.isEmpty() ? "" : " (" + children + ")");
  }

  private static String when(final SchoolEventSuggestion.Details details) {
    return details.endDate() == null || details.endDate().equals(details.startDate())
        ? DAY.format(details.startDate())
        : DAY.format(details.startDate()) + " to " + DAY.format(details.endDate());
  }

  private static String time(final SchoolEventSuggestion.Details details) {
    if (details.startTime() == null) {
      return "all day";
    }
    return details.endTime() == null ? details.startTime()
        : details.startTime() + "–" + details.endTime();
  }

  private void send(final Parent to, final String subject, final String body) {
    if (!properties.emailEnabled() || to.email() == null || to.email().isBlank()
        || !CoparentAccessPolicy.ACTIVE.equals(to.status())) {
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
      LOG.warn("Could not send school events email: {}", exception.getMessage());
    }
  }
}
