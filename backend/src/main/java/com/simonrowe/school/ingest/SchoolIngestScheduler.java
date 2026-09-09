package com.simonrowe.school.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives school ingestion on a schedule.
 *
 * <p>Gated on {@code school.enabled}, so a deploy with the feature off registers no scheduled work
 * and makes no outbound requests to the school's website.
 *
 * <p>Mirrors {@code AggregationScheduler} rather than introducing a Temporal workflow. A Temporal
 * flow would bring better retry semantics, but it would also put the school's credentials into the
 * container that shares an image with the Docker-socket-holding deployer — and this repository has
 * built three separate compose-parsing tests to stop exactly that.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: a website crawl honouring a ten-second delay per
 * page takes over half an hour, and {@code fixedRate} would happily start the next pass while the
 * previous one was still walking the site.
 */
@Component
@ConditionalOnProperty(prefix = "school", name = "enabled", havingValue = "true")
public class SchoolIngestScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolIngestScheduler.class);
  // Properties with defaults rather than constants, so cadence can change without a deploy.
  // Sized by what each source costs: the calendar is one request, the mailbox is a list plus a
  // fetch per changed message, and the website is a ~30 minute crawl because robots.txt asks
  // for ten seconds a page — running that hourly would mean crawling almost continuously.
  private static final String CALENDAR_INTERVAL = "${school.schedule.calendar-ms:21600000}";
  private static final String WEBSITE_INTERVAL = "${school.schedule.website-ms:43200000}";
  private static final String GMAIL_INTERVAL = "${school.schedule.gmail-ms:1800000}";
  private static final String STARTUP_DELAY = "120000";
  private static final String STARTUP_DELAY_WEBSITE = "240000";
  private static final String STARTUP_DELAY_GMAIL = "360000";

  private final SchoolIngestService ingestService;
  private final GmailIngestService gmailIngestService;

  public SchoolIngestScheduler(
      final SchoolIngestService ingestService, final GmailIngestService gmailIngestService) {
    this.ingestService = ingestService;
    this.gmailIngestService = gmailIngestService;
  }

  /**
   * Populates the staff directory as soon as the application is up.
   *
   * <p>Not deferred to the first scheduled crawl: the directory starts empty, an empty directory
   * makes the name gate block every name, and the gate runs on the answer path as well as the
   * ingest path. Leaving it empty for a day would make the assistant withhold public answers it
   * should be giving.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void primeStaffDirectory() {
    try {
      ingestService.refreshStaffDirectory();
    } catch (RuntimeException e) {
      LOG.warn("Could not prime the staff directory at startup: {}", e.getMessage());
    }
  }

  /** Refreshes the calendar. Cheap - one request - so it runs often. */
  @Scheduled(initialDelayString = STARTUP_DELAY, fixedDelayString = CALENDAR_INTERVAL)
  public void ingestCalendar() {
    try {
      ingestService.ingestCalendar();
    } catch (RuntimeException e) {
      LOG.warn("Calendar ingest failed: {}", e.getMessage());
    }
  }

  /**
   * Syncs the school mailbox. Every 30 minutes by default — a weekly newsletter does not need
   * faster, and each pass costs one list call plus a fetch per changed message.
   *
   * <p>No-ops when no credential is configured, so this is safe to schedule unconditionally
   * alongside the public-tier work.
   */
  @Scheduled(
      initialDelayString = STARTUP_DELAY_GMAIL, fixedDelayString = GMAIL_INTERVAL)
  public void ingestMail() {
    try {
      gmailIngestService.sync();
    } catch (RuntimeException e) {
      LOG.warn("Gmail ingest failed: {}", e.getMessage());
    }
  }

  /** Crawls the website. Slow and rarely changes, so daily. */
  @Scheduled(
      initialDelayString = STARTUP_DELAY_WEBSITE, fixedDelayString = WEBSITE_INTERVAL)
  public void ingestWebsite() {
    try {
      ingestService.refreshStaffDirectory();
      ingestService.ingestWebsite();
    } catch (RuntimeException e) {
      LOG.warn("Website ingest failed: {}", e.getMessage());
    }
  }
}
