package com.simonrowe.factory.pageread.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.pageread.config.PageReadTaskQueues;
import com.simonrowe.factory.pageread.domain.PageReadOutcome;
import com.simonrowe.factory.pageread.domain.PageReadResult;
import com.simonrowe.factory.pageread.workflow.PageReadActivities;
import com.simonrowe.factory.pageread.workflow.PageReadWorkflowImpl;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PageReadServiceTest {

  private static final String URL = "https://openai.com/index/scaling-git-at-openai/";

  private TestWorkflowEnvironment environment;

  @AfterEach
  void closeEnvironment() {
    if (environment != null) {
      environment.close();
    }
  }

  private PageReadService service(final PageReadActivities reader) {
    environment = TestWorkflowEnvironment.newInstance();
    environment.newWorker(PageReadTaskQueues.PAGE_READ)
        .registerWorkflowImplementationTypes(PageReadWorkflowImpl.class);
    environment.newWorker(PageReadTaskQueues.PAGE_READER).registerActivitiesImplementations(reader);
    environment.start();
    return new PageReadService(environment.getWorkflowClient(), () -> 1);
  }

  @Test
  void completedReadIsReusedWithoutReadingThePageAgain() {
    AtomicInteger reads = new AtomicInteger();
    PageReadService service = service(input -> {
      reads.incrementAndGet();
      return new PageReadResult(PageReadOutcome.READ, 200, input.url(), "Title", "Body.");
    });

    PageReadResponse first = service.read(URL, Duration.ofSeconds(30));
    PageReadResponse second = service.read(URL, Duration.ofSeconds(30));

    assertThat(first.state()).isEqualTo(PageReadState.COMPLETED);
    assertThat(first.result().text()).isEqualTo("Body.");
    assertThat(second).isEqualTo(first);
    assertThat(reads).hasValue(1);
  }

  @Test
  void answersRunningWhenTheWaitRunsOutFirst() throws InterruptedException {
    CountDownLatch release = new CountDownLatch(1);
    PageReadService service = service(input -> {
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return new PageReadResult(PageReadOutcome.READ, 200, input.url(), "Title", "Body.");
    });

    PageReadResponse response = service.read(URL, Duration.ofMillis(300));
    release.countDown();

    assertThat(response.state()).isEqualTo(PageReadState.RUNNING);
    assertThat(response.result()).isNull();
  }

  @Test
  void failedReadIsReportedAndMayBeStartedAgain() {
    AtomicInteger reads = new AtomicInteger();
    PageReadService service = service(input -> {
      if (reads.incrementAndGet() <= 2) {
        throw new IllegalStateException("browser crashed");
      }
      return new PageReadResult(PageReadOutcome.READ, 200, input.url(), "Title", "Body.");
    });

    assertThat(service.read(URL, Duration.ofSeconds(60)).state()).isEqualTo(PageReadState.FAILED);
    assertThat(service.read(URL, Duration.ofSeconds(60)).state())
        .isEqualTo(PageReadState.COMPLETED);
  }

  /** Otherwise the read waits out the whole wait on a queue nobody polls. */
  @Test
  void refusesAtOnceWhenNoPageReaderIsPolling() {
    AtomicInteger reads = new AtomicInteger();
    service(input -> {
      reads.incrementAndGet();
      return new PageReadResult(PageReadOutcome.READ, 200, input.url(), "Title", "Body.");
    });
    PageReadService noReader = new PageReadService(environment.getWorkflowClient(), () -> 0);

    assertThatThrownBy(() -> noReader.read(URL, Duration.ofSeconds(30)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("No page-reader");
    assertThat(reads).hasValue(0);
  }

  @Test
  void readsAnywayWhenTemporalCannotSayHowManyArePolling() {
    service(input -> new PageReadResult(PageReadOutcome.READ, 200, input.url(), "Title", "Body."));
    PageReadService unknown = new PageReadService(environment.getWorkflowClient(), () -> null);

    assertThat(unknown.read(URL, Duration.ofSeconds(30)).state())
        .isEqualTo(PageReadState.COMPLETED);
  }

  @Test
  void theWorkflowIdIsTheAddressHashed() {
    assertThat(PageReadService.workflowId(URL))
        .startsWith("page-read-")
        .hasSize("page-read-".length() + 64)
        .isEqualTo(PageReadService.workflowId(URL))
        .isNotEqualTo(PageReadService.workflowId(URL + "?x"));
  }
}
