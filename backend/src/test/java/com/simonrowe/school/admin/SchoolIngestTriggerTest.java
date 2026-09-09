package com.simonrowe.school.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.simonrowe.school.ingest.GmailIngestService;
import com.simonrowe.school.ingest.SchoolIngestService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The manual ingest trigger behind the admin console's "run now" buttons.
 *
 * <p>{@link SchoolIngestTrigger#run} is exercised directly rather than through
 * {@link SchoolIngestTrigger#trigger}, which hands the work to a background executor — a test
 * that submitted and then waited would be timing-dependent for no gain, since the dispatch and
 * the guard are the two things worth pinning and both are observable synchronously.
 */
class SchoolIngestTriggerTest {

  private SchoolIngestService ingestService;
  private GmailIngestService gmailIngestService;
  private SchoolIngestTrigger trigger;

  @BeforeEach
  void setUp() {
    ingestService = mock(SchoolIngestService.class);
    gmailIngestService = mock(GmailIngestService.class);
    trigger = new SchoolIngestTrigger(ingestService, gmailIngestService);
  }

  @Test
  @DisplayName("nothing is running before anything is asked for")
  void nothingRunsInitially() {
    assertThat(trigger.isRunning("calendar")).isFalse();
    assertThat(trigger.isRunning("website")).isFalse();
    assertThat(trigger.isRunning("gmail")).isFalse();
  }

  @Test
  @DisplayName("an unknown source is refused rather than silently started")
  void unknownSourceIsRefused() {
    assertThat(trigger.trigger("nonsense")).isFalse();
    assertThat(trigger.trigger(null)).isFalse();
    assertThat(trigger.isRunning("nonsense")).isFalse();
    assertThat(trigger.isRunning(null)).isFalse();
  }

  @Test
  @DisplayName("a second trigger for the same source is refused while the first is in flight")
  void secondTriggerIsRefusedWhileRunning() throws Exception {
    // The guard is what stops two crawls hitting a small school's hosting at once. It is the
    // whole reason this class exists rather than the controller calling the service directly.
    //
    // Latched rather than timed. An earlier cut asserted against isRunning() straight after
    // triggering, which passed alone and failed inside the full suite: the run can finish on
    // the executor thread before the assertion reads the flag, so the test was really timing
    // the machine. Blocking the run makes "in flight" a fact rather than a race.
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    when(ingestService.ingestCalendar()).thenAnswer(invocation -> {
      started.countDown();
      release.await(5, TimeUnit.SECONDS);
      return 0;
    });

    assertThat(trigger.trigger("calendar")).isTrue();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    try {
      assertThat(trigger.isRunning("calendar")).isTrue();
      assertThat(trigger.trigger("calendar")).isFalse();
      // A different source is unaffected: the guards are per-source, so a crawl in progress
      // must not block a mail sync.
      assertThat(trigger.isRunning("gmail")).isFalse();
    } finally {
      release.countDown();
    }
  }

  @Test
  @DisplayName("calendar dispatches only the calendar ingest")
  void calendarDispatches() {
    trigger.run("calendar", new AtomicBoolean(true));

    verify(ingestService).ingestCalendar();
  }

  @Test
  @DisplayName("website refreshes the staff directory before crawling")
  void websiteRefreshesStaffFirst() {
    // Order matters historically: the directory is populated by the crawl, and running the
    // crawl against a stale one is what left the public tier thin.
    trigger.run("website", new AtomicBoolean(true));

    final var order = org.mockito.Mockito.inOrder(ingestService);
    order.verify(ingestService).refreshStaffDirectory();
    order.verify(ingestService).ingestWebsite();
  }

  @Test
  @DisplayName("gmail dispatches only the mail sync")
  void gmailDispatches() throws Exception {
    trigger.run("gmail", new AtomicBoolean(true));

    verify(gmailIngestService).sync();
  }

  @Test
  @DisplayName("an unknown source dispatches nothing at all")
  void unknownSourceDispatchesNothing() {
    trigger.run("nonsense", new AtomicBoolean(true));

    org.mockito.Mockito.verifyNoInteractions(ingestService, gmailIngestService);
  }

  @Test
  @DisplayName("the guard is cleared even when the run throws, or the source jams forever")
  void guardIsClearedOnFailure() {
    // Without the finally block a single failed crawl would leave "website" permanently
    // reporting in-progress, and the button would never work again until a restart.
    doThrow(new IllegalStateException("crawl exploded")).when(ingestService).ingestWebsite();
    final AtomicBoolean flag = new AtomicBoolean(true);

    trigger.run("website", flag);

    assertThat(flag).isFalse();
  }

  @Test
  @DisplayName("the guard is cleared on a clean run too")
  void guardIsClearedOnSuccess() {
    final AtomicBoolean flag = new AtomicBoolean(true);

    trigger.run("calendar", flag);

    assertThat(flag).isFalse();
  }

  @Test
  @DisplayName("source names are matched case-insensitively")
  void sourceNamesAreCaseInsensitive() {
    assertThat(trigger.trigger("CALENDAR")).isTrue();
  }
}
