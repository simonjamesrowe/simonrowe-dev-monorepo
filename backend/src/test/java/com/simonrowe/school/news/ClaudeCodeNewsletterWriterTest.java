package com.simonrowe.school.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factoryadmin.ContentWritingClient;
import com.simonrowe.factoryadmin.ContentWritingClient.State;
import com.simonrowe.factoryadmin.ContentWritingClient.WritingJob;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClaudeCodeNewsletterWriterTest {

  private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");
  private static final String DOC_ID =
      "33a21e60150abccd762e26d9d3e7a14125baf50f4ef9d071bc505a44c1ddca0c";

  private final ContentWritingClient client = mock(ContentWritingClient.class);

  private static SchoolDocument newsletter(final String hash) {
    return new SchoolDocument(DOC_ID, SchoolSourceType.WEBSITE_PAGE, "url", "Newsletter", "body",
        NOW, NOW, Visibility.PUBLIC, null, null, null, null, false, List.of(), hash, null);
  }

  /** A clock that can be moved on between calls. */
  private static final class MovableClock extends Clock {
    private Instant now = NOW;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  @DisplayName("reads before submitting, so a failed job is not restarted every run")
  void readsBeforeSubmitting() {
    when(client.status(anyString())).thenReturn(new WritingJob("j", State.FAILED, null));
    final ClaudeCodeNewsletterWriter writer =
        new ClaudeCodeNewsletterWriter(client, Clock.systemUTC(), Duration.ofMinutes(30));

    assertThat(writer.attempt(newsletter("abc"), "prompt").action())
        .isEqualTo(ClaudeCodeNewsletterWriter.Action.FALL_BACK);

    verify(client, never()).submit(anyString(), anyString());
  }

  @Test
  @DisplayName("waits within the bound and falls back once it has passed")
  void boundsTheWait() {
    when(client.status(anyString())).thenReturn(new WritingJob("j", State.RUNNING, null));
    final MovableClock clock = new MovableClock();
    final ClaudeCodeNewsletterWriter writer =
        new ClaudeCodeNewsletterWriter(client, clock, Duration.ofMinutes(30));

    assertThat(writer.attempt(newsletter("abc"), "p").action())
        .isEqualTo(ClaudeCodeNewsletterWriter.Action.WAITING);
    clock.now = NOW.plus(Duration.ofMinutes(31));
    assertThat(writer.attempt(newsletter("abc"), "p").action())
        .isEqualTo(ClaudeCodeNewsletterWriter.Action.FALL_BACK);
  }

  @Test
  @DisplayName("stops timing a job once it has completed")
  void forgetsCompletedJobs() {
    when(client.status(anyString()))
        .thenReturn(new WritingJob("j", State.RUNNING, null))
        .thenReturn(new WritingJob("j", State.COMPLETED, "{}"));
    final ClaudeCodeNewsletterWriter writer =
        new ClaudeCodeNewsletterWriter(client, Clock.systemUTC(), Duration.ofMinutes(30));

    writer.attempt(newsletter("abc"), "p");
    assertThat(writer.tracked()).isEqualTo(1);
    assertThat(writer.attempt(newsletter("abc"), "p").text()).isEqualTo("{}");
    assertThat(writer.tracked()).isZero();
  }

  @Test
  @DisplayName("an edited newsletter gets a new job, and every id fits the factory's charset")
  void jobIdFollowsTheText() {
    final String first = ClaudeCodeNewsletterWriter.jobId(newsletter("a".repeat(64)));
    final String edited = ClaudeCodeNewsletterWriter.jobId(newsletter("b".repeat(64)));

    assertThat(first).isNotEqualTo(edited);
    assertThat(first).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    assertThat(ClaudeCodeNewsletterWriter.jobId(newsletter(null)))
        .matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  }
}
