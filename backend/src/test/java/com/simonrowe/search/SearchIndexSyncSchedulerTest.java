package com.simonrowe.search;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchIndexSyncSchedulerTest {

  private IndexService indexService;
  private SearchIndexSyncScheduler scheduler;

  @BeforeEach
  void setUp() {
    indexService = mock(IndexService.class);
    scheduler = new SearchIndexSyncScheduler(indexService);
  }

  @Test
  void syncOnStartupCallsFullSync() throws Exception {
    scheduler.syncOnStartup();

    verify(indexService).fullSyncSiteIndex();
    verify(indexService).fullSyncBlogIndex();
  }

  @Test
  void scheduledSyncCallsFullSync() throws Exception {
    scheduler.scheduledSync();

    verify(indexService).fullSyncSiteIndex();
    verify(indexService).fullSyncBlogIndex();
  }

  @Test
  void syncHandlesIoException() throws Exception {
    doThrow(new IOException("ES down")).when(indexService).fullSyncSiteIndex();

    scheduler.syncOnStartup();

    verify(indexService).fullSyncSiteIndex();
  }
}
