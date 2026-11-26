package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.Parent;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SchoolSuggestionMailerTest {

  private final JavaMailSender sender = mock(JavaMailSender.class);
  private final ObjectId child = new ObjectId();

  @Test
  void sendsOneSummaryToEachActiveParent() {
    mailer(true).needsApproval(List.of(parent("a@example.com", "active"),
            parent("b@example.com", "active"), parent("c@example.com", "invited")),
        List.of(suggestion("Local Area Walk", "09:15", "10:45", null),
            suggestion("Winter Fair", null, null, SchoolEventSuggestion.Review.REMOVED)),
        Map.of(child, "Robin"));

    final ArgumentCaptor<SimpleMailMessage> sent =
        ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender, times(2)).send(sent.capture());
    final SimpleMailMessage message = sent.getValue();
    assertThat(message.getSubject()).isEqualTo("2 school events need a look");
    assertThat(message.getText())
        .contains("- Fri 27 Nov, 09:15–10:45: Local Area Walk (Robin)")
        .contains("- No longer on the school calendar: Fri 27 Nov, all day: Winter Fair")
        .contains("https://coparents.example/calendar?school=1");
  }

  @Test
  void sendsNothingWhenMailIsOff() {
    mailer(false).needsApproval(List.of(parent("a@example.com", "active")),
        List.of(suggestion("Walk", null, null, null)), Map.of());
    verify(sender, never()).send(any(SimpleMailMessage.class));
  }

  private SchoolSuggestionMailer mailer(final boolean enabled) {
    return new SchoolSuggestionMailer(sender, new CoparentProperties(true, "coparent",
        "https://coparents.example", null, "noreply@example.com", enabled, null, null));
  }

  private SchoolEventSuggestion suggestion(final String title, final String start,
      final String end, final SchoolEventSuggestion.Review review) {
    final LocalDate day = LocalDate.of(2026, 11, 27);
    return new SchoolEventSuggestion(new ObjectId(), new ObjectId(), List.of("x"), List.of(),
        List.of(child), new SchoolEventSuggestion.Details(title, day, day, start, end, null, null,
        null), "f", SchoolEventSuggestion.Status.PENDING, review, null, null, null, null, null,
        null);
  }

  private static Parent parent(final String email, final String status) {
    return new Parent(new ObjectId(), "auth0|x", new ObjectId(), "Alex Example", email,
        "co-parent", status, null, null, null, null, null);
  }
}
