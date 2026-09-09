package com.simonrowe.school.admin;

import com.simonrowe.school.ingest.GmailIngestService;
import com.simonrowe.school.ingest.SchoolIngestService;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs an ingest on demand from the admin console.
 *
 * <p>Asynchronous, because a website crawl takes about half an hour at the ten-second delay the
 * school's robots.txt asks for — a synchronous endpoint would time out long before it finished
 * and leave the operator with no idea whether it was still running.
 *
 * <p>One run per source at a time. Two concurrent website crawls would double the request rate
 * against a small school's hosting, which is precisely what the crawl delay exists to avoid.
 *
 * <p>Uses its own single-thread executor rather than {@code @Async}. This application has no
 * {@code @EnableAsync} anywhere, so an {@code @Async} method would run <b>synchronously</b> with
 * no warning — the endpoint would block for the length of a crawl and the caller would time out
 * having been told nothing. A local executor cannot be switched off by a missing annotation.
 */
@Component
public class SchoolIngestTrigger {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolIngestTrigger.class);

  private final SchoolIngestService ingestService;
  private final GmailIngestService gmailIngestService;

  private final AtomicBoolean calendarRunning = new AtomicBoolean();
  private final AtomicBoolean websiteRunning = new AtomicBoolean();
  private final AtomicBoolean gmailRunning = new AtomicBoolean();

  /**
   * Single-threaded on purpose: the three sources hit the same school and the same mailbox, and
   * running a crawl beside a mail sync buys nothing. Daemon threads so a shutdown is not held
   * open by a half-finished crawl.
   */
  private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
    final Thread thread = new Thread(runnable, "school-ingest-trigger");
    thread.setDaemon(true);
    return thread;
  });

  public SchoolIngestTrigger(
      final SchoolIngestService ingestService, final GmailIngestService gmailIngestService) {
    this.ingestService = ingestService;
    this.gmailIngestService = gmailIngestService;
  }

  /**
   * Whether a source is currently mid-run.
   *
   * @param source {@code calendar}, {@code website} or {@code gmail}
   * @return true when a run is in progress
   */
  public boolean isRunning(final String source) {
    // Resolved once. Calling flagFor twice was safe only because it is a pure switch, which is
    // not a property a reader (or an analyser) should have to verify to trust the line.
    final AtomicBoolean flag = flagFor(source);
    return flag != null && flag.get();
  }

  /**
   * Starts a run if one is not already going.
   *
   * @param source {@code calendar}, {@code website} or {@code gmail}
   * @return true when a run was started, false when one was already in progress
   */
  public boolean trigger(final String source) {
    final AtomicBoolean flag = flagFor(source);
    if (flag == null || !flag.compareAndSet(false, true)) {
      return false;
    }
    final String normalised = source.toLowerCase(Locale.ROOT);
    executor.submit(() -> run(normalised, flag));
    return true;
  }

  void run(final String source, final AtomicBoolean flag) {
    try {
      LOG.info("Manual {} ingest started", source);
      switch (source) {
        case "calendar" -> ingestService.ingestCalendar();
        case "website" -> {
          ingestService.refreshStaffDirectory();
          ingestService.ingestWebsite();
        }
        case "gmail" -> gmailIngestService.sync();
        default -> LOG.warn("Unknown ingest source {}", source);
      }
      LOG.info("Manual {} ingest finished", source);
    } catch (RuntimeException e) {
      LOG.warn("Manual {} ingest failed: {}", source, e.getMessage());
    } finally {
      flag.set(false);
    }
  }

  private AtomicBoolean flagFor(final String source) {
    if (source == null) {
      return null;
    }
    return switch (source.toLowerCase(Locale.ROOT)) {
      case "calendar" -> calendarRunning;
      case "website" -> websiteRunning;
      case "gmail" -> gmailRunning;
      default -> null;
    };
  }
}
