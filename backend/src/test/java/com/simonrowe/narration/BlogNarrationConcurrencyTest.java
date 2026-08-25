package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = {
    "narration.enabled=true",
    "narration.recovery-delay=1h"
})
class BlogNarrationConcurrencyTest extends AbstractIntegrationTest {

  @Autowired private BlogRepository blogRepository;
  @Autowired private NarrationRepository narrationRepository;
  @Autowired private NarrationService narrationService;
  @Autowired private NarrationRestoreValidator restoreValidator;

  @MockitoBean private NarrationProvider provider;
  @MockitoBean private NarrationRequestPublisher publisher;

  @BeforeEach
  void setUp() {
    blogRepository.deleteAll();
    narrationRepository.deleteAll();
    restoreValidator.ensureIndexes();
    when(provider.isConfigured()).thenReturn(true);
  }

  @AfterEach
  void cleanUp() {
    blogRepository.deleteAll();
    narrationRepository.deleteAll();
  }

  @Test
  void oneHundredSimultaneousRequestsCreateAndPublishOnlyOnce() throws Exception {
    blogRepository.save(blog());
    List<Callable<NarrationService.RequestResult>> requests =
        java.util.stream.IntStream.range(0, 100)
            .mapToObj(ignored -> (Callable<NarrationService.RequestResult>)
                () -> narrationService.request(NarrationContentType.BLOG, "blog-1"))
            .toList();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<NarrationService.RequestResult> results = executor.invokeAll(requests)
          .stream()
          .map(future -> {
            try {
              return future.get();
            } catch (Exception ex) {
              throw new IllegalStateException(ex);
            }
          })
          .toList();

      assertThat(results).filteredOn(NarrationService.RequestResult::accepted)
          .hasSize(1);
    }
    assertThat(narrationRepository.count()).isEqualTo(1);
    String narrationId = narrationRepository.findAll().getFirst().id();
    verify(publisher, times(1)).publish(narrationId);
  }

  @Test
  void concurrentKafkaRedeliveriesAcquireOnlyOneAtomicClaim() throws Exception {
    Narration narration = new Narration(
        "narration-1", NarrationContentType.BLOG, "blog-1", 100, "voice", "en-GB", "MP3",
        "narrations/narration-1.mp3", Instant.now());
    narrationRepository.insert(narration);
    List<Callable<Boolean>> claims = java.util.stream.IntStream.range(0, 100)
        .mapToObj(ignored -> (Callable<Boolean>)
            () -> narrationService.claim("narration-1", Instant.now()).isPresent())
        .toList();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      long acquired = executor.invokeAll(claims).stream().filter(future -> {
        try {
          return future.get();
        } catch (Exception ex) {
          throw new IllegalStateException(ex);
        }
      }).count();
      assertThat(acquired).isEqualTo(1);
    }
    assertThat(narrationRepository.findById("narration-1").orElseThrow().attemptCount())
        .isEqualTo(1);
  }

  private static Blog blog() {
    Instant now = Instant.parse("2026-08-11T10:00:00Z");
    return new Blog("blog-1", "Concurrency", "Short", "A narratable blog body.",
        true, null, now, now, List.of(), List.of(), BlogContentType.ENGINEERING);
  }
}
