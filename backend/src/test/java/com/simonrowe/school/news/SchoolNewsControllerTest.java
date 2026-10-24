package com.simonrowe.school.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.school.SchoolProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchoolNewsControllerTest {

  private static final SchoolProperties ENABLED = new SchoolProperties(
      true, null, null, null, null, null, null, 0, null, null, null, 0, null, null);
  private static final SchoolProperties DISABLED = new SchoolProperties(
      false, null, null, null, null, null, null, 0, null, null, null, 0, null, null);

  private final SchoolNewsService news = mock(SchoolNewsService.class);

  @Test
  @DisplayName("projects a summarised newsletter without the summary's bookkeeping")
  void projectsNewsletter() {
    final Instant at = Instant.parse("2026-10-23T00:00:00Z");
    final NewsletterSummary.Point point =
        new NewsletterSummary.Point("Pupil Parliament elections.", List.of("Year 2"));
    when(news.newsletters(SchoolNewsController.DEFAULT_NEWSLETTERS)).thenReturn(List.of(
        new SchoolNewsService.Newsletter("171", "Newsletter", "https://x/?id=171", at,
            new NewsletterSummary("171", "hash", "Half term", List.of(point), "m", at))));

    final var body = new SchoolNewsController(news, ENABLED).newsletters(null).getBody();

    assertThat(body).containsExactly(new SchoolNewsController.NewsletterResponse(
        "171", "Newsletter", "https://x/?id=171", at, "Half term", List.of(point)));
  }

  @Test
  @DisplayName("a newsletter waiting for its summary has no headline and no points")
  void unsummarised() {
    when(news.newsletters(SchoolNewsController.DEFAULT_NEWSLETTERS)).thenReturn(List.of(
        new SchoolNewsService.Newsletter("165", "Newsletter", "u", Instant.EPOCH, null)));

    final var body = new SchoolNewsController(news, ENABLED).newsletters(null).getBody();

    assertThat(body).singleElement().satisfies(newsletter -> {
      assertThat(newsletter.headline()).isNull();
      assertThat(newsletter.points()).isEmpty();
    });
  }

  @Test
  @DisplayName("caps the limit a visitor can ask for")
  void capsTheLimit() {
    new SchoolNewsController(news, ENABLED).newsletters(500);
    new SchoolNewsController(news, ENABLED).messages(500);

    verify(news).newsletters(SchoolNewsController.MAX_NEWSLETTERS);
    verify(news).recentMessages(SchoolNewsController.MAX_MESSAGES);
  }

  @Test
  @DisplayName("reports 503 and reads nothing when Term Time is switched off")
  void disabled() {
    final SchoolNewsController controller = new SchoolNewsController(news, DISABLED);

    assertThat(controller.newsletters(null).getStatusCode().value()).isEqualTo(503);
    assertThat(controller.messages(null).getStatusCode().value()).isEqualTo(503);
    verifyNoInteractions(news);
  }
}
