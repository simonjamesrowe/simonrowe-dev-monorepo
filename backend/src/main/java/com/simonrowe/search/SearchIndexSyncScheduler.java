package com.simonrowe.search;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class SearchIndexSyncScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(SearchIndexSyncScheduler.class);

  private final IndexService indexService;

  public SearchIndexSyncScheduler(final IndexService indexService) {
    this.indexService = indexService;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(2)
  public void syncOnStartup() {
    LOG.info("Running search index sync on startup");
    runFullSync();
  }

  @Scheduled(cron = "${search.sync.cron:0 0 */4 * * *}")
  public void scheduledSync() {
    LOG.info("Running scheduled search index sync");
    runFullSync();
  }

  private void runFullSync() {
    long start = System.currentTimeMillis();
    try {
      indexService.fullSyncSiteIndex();
      indexService.fullSyncBlogIndex();
      long duration = System.currentTimeMillis() - start;
      LOG.info("Full search index sync completed in {}ms", duration);
    } catch (IOException e) {
      long duration = System.currentTimeMillis() - start;
      LOG.error("Full search index sync failed after {}ms", duration, e);
    }
  }
}
