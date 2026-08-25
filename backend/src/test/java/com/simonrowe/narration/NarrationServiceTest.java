package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class NarrationServiceTest {

  @Mock private BlogRepository blogRepository;
  @Mock private NarrationRepository narrationRepository;
  @Mock private NarrationProvider provider;
  @Mock private NarrationRequestPublisher publisher;
  @Mock private NarrationStorage storage;
  @Mock private MongoTemplate mongoTemplate;

  private SimpleMeterRegistry metrics;
  private NarrationService service;

  @BeforeEach
  void setUp() {
    metrics = new SimpleMeterRegistry();
    service = service(properties(50_000));
    lenient().when(provider.isConfigured()).thenReturn(true);
  }

  @Test
  void publishedUncachedBlogIsNotRequestedUntilExplicitPost() {
    Blog blog = blog("Content", true);
    when(blogRepository.findByIdAndPublishedTrue("blog-1")).thenReturn(Optional.of(blog));
    when(narrationRepository.findById(any())).thenReturn(Optional.empty());

    assertThat(service.getStatus(NarrationContentType.BLOG, "blog-1", null, 0).state())
        .isEqualTo(NarrationResponse.PublicState.NOT_REQUESTED);
    verify(publisher, never()).publish(any());
  }

  @Test
  void missingOrUnpublishedBlogIsNotDisclosed() {
    when(blogRepository.findByIdAndPublishedTrue("blog-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getStatus(NarrationContentType.BLOG, "blog-1", null, 0))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  void explicitRequestCreatesDeterministicQueuedRecord() {
    Blog blog = blog("Same content", true);
    when(blogRepository.findByIdAndPublishedTrue("blog-1")).thenReturn(Optional.of(blog));
    when(narrationRepository.findById(any())).thenReturn(Optional.empty());
    when(narrationRepository.insert(any(Narration.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    NarrationService.RequestResult first = service.request(NarrationContentType.BLOG, "blog-1");

    assertThat(first.accepted()).isTrue();
    assertThat(first.response().state()).isEqualTo(NarrationResponse.PublicState.QUEUED);
    verify(publisher).publish(any());
  }

  @Test
  void readyAssetBypassesProviderAndTracksReuse() {
    Blog blog = blog("Same content", true);
    Narration narration = matchingNarration(blog);
    narration.markReady(new NarrationStorage.StoredNarration(
        "/uploads/narrations/id/narration.mp3", 4, "checksum", 1), Instant.now());
    when(blogRepository.findByIdAndPublishedTrue("blog-1")).thenReturn(Optional.of(blog));
    when(narrationRepository.findById(narration.id())).thenReturn(Optional.of(narration));
    when(storage.isValid(narration)).thenReturn(true);

    NarrationService.RequestResult result = service.request(NarrationContentType.BLOG, "blog-1");

    assertThat(result.accepted()).isFalse();
    assertThat(result.response().state()).isEqualTo(NarrationResponse.PublicState.READY);
    assertThat(narration.reuseCount()).isEqualTo(1);
    verify(provider, never()).start(any(), any());
    assertThat(metrics.counter("narration.requests", "result", "reused").count())
        .isEqualTo(1);
  }

  @Test
  void retryableFailureQueuesOnlyOnAnotherExplicitRequest() {
    Blog blog = blog("Same content", true);
    Narration narration = matchingNarration(blog);
    narration.markFailed("SAFE_FAILURE", true, Instant.now());
    when(blogRepository.findByIdAndPublishedTrue("blog-1")).thenReturn(Optional.of(blog));
    when(narrationRepository.findById(narration.id())).thenReturn(Optional.of(narration));

    NarrationService.RequestResult result = service.request(NarrationContentType.BLOG, "blog-1");

    assertThat(result.accepted()).isTrue();
    assertThat(narration.status()).isEqualTo(NarrationStatus.QUEUED);
    verify(publisher).publish(narration.id());
  }

  @Test
  void providerDisabledAndOversizedContentFailBeforePersistence() {
    Blog blog = blog("Content", true);
    when(blogRepository.findByIdAndPublishedTrue("blog-1")).thenReturn(Optional.of(blog));
    when(provider.isConfigured()).thenReturn(false);

    assertThat(service.request(NarrationContentType.BLOG, "blog-1").response().state())
        .isEqualTo(NarrationResponse.PublicState.UNAVAILABLE);
    verify(narrationRepository, never()).insert(any(Narration.class));

    NarrationService tiny = service(properties(5));
    assertThatThrownBy(() -> tiny.descriptor(NarrationContentType.BLOG, blog.id()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("413");
  }

  @Test
  void invalidReadyAssetBecomesSanitizedRetryableFailure() {
    Blog blog = blog("Same content", true);
    Narration narration = matchingNarration(blog);
    narration.markReady(new NarrationStorage.StoredNarration(
        "/uploads/narrations/id/narration.mp3", 4, "checksum", 1), Instant.now());
    when(blogRepository.findByIdAndPublishedTrue("blog-1")).thenReturn(Optional.of(blog));
    when(narrationRepository.findById(narration.id())).thenReturn(Optional.of(narration));
    when(storage.isValid(narration)).thenReturn(false);

    NarrationResponse response = service.getStatus(NarrationContentType.BLOG, "blog-1", null, 0);

    assertThat(response.state()).isEqualTo(NarrationResponse.PublicState.FAILED);
    assertThat(response.retryable()).isTrue();
    assertThat(response.message()).doesNotContain("AUDIO_MISSING_OR_INVALID");
  }

  private NarrationService service(final NarrationProperties props) {
    return new NarrationService(narrationRepository, props, provider, publisher, storage,
        mongoTemplate, metrics,
        List.of(new BlogNarrationSource(
            blogRepository, new NarrationScriptBuilder(), props)));
  }

  /**
   * Builds the narration a blog's current content would produce, without going through the
   * service — the service now resolves the blog through the repository, and these tests
   * build the expected narration before stubbing that lookup.
   */
  private Narration matchingNarration(final Blog blog) {
    NarrationSource.NarrationDescriptor descriptor = new BlogNarrationSource(
        blogRepository, new NarrationScriptBuilder(), properties(50_000))
        .scriptForBlog(blog);
    return new Narration(descriptor.id(), NarrationContentType.BLOG, blog.id(),
        descriptor.script().length(),
        "voice", "en-GB", "MP3", "narrations/" + descriptor.id() + ".mp3",
        Instant.now());
  }

  private static NarrationProperties properties(final int maxCharacters) {
    return new NarrationProperties(true, "project", "123456789012", "global",
        "voice", "en-GB",
        "bucket", maxCharacters, 1_000_000, Duration.ofMillis(1),
        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
  }

  private static Blog blog(final String content, final boolean published) {
    Instant now = Instant.now();
    return new Blog("blog-1", "Title", "Short", content, published,
        null, now, now, List.of(), List.of(), BlogContentType.ENGINEERING);
  }
}
