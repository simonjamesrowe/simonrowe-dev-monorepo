package com.simonrowe.narration;

import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BlogNarrationService {

  private static final String AUDIO_ENCODING = "MP3";
  private static final Duration STATUS_POLL_INTERVAL = Duration.ofMillis(500);

  private final BlogRepository blogRepository;
  private final NarrationRepository narrationRepository;
  private final BlogNarrationScriptBuilder scriptBuilder;
  private final NarrationProperties properties;
  private final NarrationProvider provider;
  private final NarrationRequestPublisher publisher;
  private final NarrationStorage storage;
  private final MongoTemplate mongoTemplate;
  private final MeterRegistry meterRegistry;

  public BlogNarrationService(
      final BlogRepository blogRepository,
      final NarrationRepository narrationRepository,
      final BlogNarrationScriptBuilder scriptBuilder,
      final NarrationProperties properties,
      final NarrationProvider provider,
      final NarrationRequestPublisher publisher,
      final NarrationStorage storage,
      final MongoTemplate mongoTemplate,
      final MeterRegistry meterRegistry
  ) {
    this.blogRepository = blogRepository;
    this.narrationRepository = narrationRepository;
    this.scriptBuilder = scriptBuilder;
    this.properties = properties;
    this.provider = provider;
    this.publisher = publisher;
    this.storage = storage;
    this.mongoTemplate = mongoTemplate;
    this.meterRegistry = meterRegistry;
  }

  public NarrationResponse getStatus(
      final String blogId,
      final Long afterVersion,
      final int waitSeconds
  ) {
    Blog blog = publishedBlog(blogId);
    Instant deadline = Instant.now().plusSeconds(waitSeconds);
    NarrationResponse response;
    do {
      response = currentResponse(blog);
      if (afterVersion == null || response.version() != afterVersion
          || response.isTerminal() || waitSeconds == 0) {
        return response;
      }
      sleepUntilNextPoll(deadline);
    } while (Instant.now().isBefore(deadline));
    return currentResponse(blog);
  }

  public RequestResult request(final String blogId) {
    Blog blog = publishedBlog(blogId);
    NarrationDescriptor descriptor = descriptor(blog);
    Optional<Narration> existing = narrationRepository.findById(descriptor.id());
    if (existing.isPresent()) {
      Narration narration = existing.get();
      if (narration.status() == NarrationStatus.READY && storage.isValid(narration)) {
        narration.incrementReuse(Instant.now());
        narrationRepository.save(narration);
        meterRegistry.counter("narration.requests", "result", "reused").increment();
        return new RequestResult(NarrationResponse.from(narration), false);
      }
      if (narration.status() == NarrationStatus.FAILED && narration.retryable()) {
        narration.markQueued(Instant.now());
        narrationRepository.save(narration);
        publisher.publish(narration.id());
        return new RequestResult(NarrationResponse.from(narration), true);
      }
      return new RequestResult(NarrationResponse.from(narration), false);
    }
    if (!properties.isProviderConfigured() || !provider.isConfigured()) {
      return new RequestResult(NarrationResponse.unavailable(), false);
    }
    Narration narration = new Narration(
        descriptor.id(),
        blog.id(),
        descriptor.script().length(),
        properties.voiceName(),
        properties.languageCode(),
        AUDIO_ENCODING,
        "narrations/" + descriptor.id() + ".mp3",
        Instant.now());
    boolean created = false;
    try {
      narration = narrationRepository.insert(narration);
      created = true;
    } catch (DuplicateKeyException ex) {
      narration = narrationRepository.findById(descriptor.id()).orElseThrow();
    }
    if (created) {
      publisher.publish(narration.id());
      meterRegistry.counter("narration.requests", "result", "queued").increment();
    }
    return new RequestResult(NarrationResponse.from(narration), created);
  }

  public Optional<Narration> claim(final String narrationId, final Instant now) {
    Query query = Query.query(Criteria.where("_id").is(narrationId)
        .and("status").is(NarrationStatus.QUEUED));
    Update update = new Update()
        .set("status", NarrationStatus.PROCESSING)
        .set("leaseUntil", now.plus(properties.leaseDuration()))
        .set("startedAt", now)
        .set("updatedAt", now)
        .set("retryable", false)
        .inc("attemptCount", 1)
        .inc("version", 1);
    Narration claimed = mongoTemplate.findAndModify(
        query,
        update,
        FindAndModifyOptions.options().returnNew(true),
        Narration.class);
    return Optional.ofNullable(claimed);
  }

  public NarrationDescriptor descriptor(final Blog blog) {
    String script = scriptBuilder.build(blog.title(), blog.content());
    if (script.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Blog has no narratable prose");
    }
    if (script.length() > properties.maxBlogCharacters()) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "Blog is too long to narrate");
    }
    String id = scriptBuilder.fingerprint(
        script,
        properties.voiceName(),
        properties.languageCode(),
        AUDIO_ENCODING);
    return new NarrationDescriptor(id, script);
  }

  public boolean isCurrentAndPublished(final Narration narration) {
    return blogRepository.findByIdAndPublishedTrue(narration.blogId())
        .map(this::descriptor)
        .map(current -> current.id().equals(narration.id()))
        .orElse(false);
  }

  public void invalidateBlog(final String blogId) {
    String currentId = blogRepository.findByIdAndPublishedTrue(blogId)
        .map(this::descriptor)
        .map(NarrationDescriptor::id)
        .orElse(null);
    for (Narration narration : narrationRepository.findByBlogId(blogId)) {
      if (!narration.id().equals(currentId)) {
        storage.delete(narration);
        narration.markStale(Instant.now());
        narrationRepository.save(narration);
      }
    }
  }

  private NarrationResponse currentResponse(final Blog blog) {
    NarrationDescriptor descriptor = descriptor(blog);
    Optional<Narration> narration = narrationRepository.findById(descriptor.id());
    if (narration.isEmpty()) {
      return properties.isProviderConfigured() && provider.isConfigured()
          ? NarrationResponse.notRequested() : NarrationResponse.unavailable();
    }
    Narration current = narration.get();
    if (current.status() == NarrationStatus.READY && !storage.isValid(current)) {
      current.markFailed("AUDIO_MISSING_OR_INVALID", true, Instant.now());
      narrationRepository.save(current);
    }
    return NarrationResponse.from(current);
  }

  private Blog publishedBlog(final String blogId) {
    return blogRepository.findByIdAndPublishedTrue(blogId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Blog post not found"));
  }

  private static void sleepUntilNextPoll(final Instant deadline) {
    long remaining = Duration.between(Instant.now(), deadline).toMillis();
    if (remaining <= 0) {
      return;
    }
    try {
      Thread.sleep(Math.min(STATUS_POLL_INTERVAL.toMillis(), remaining));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  public record RequestResult(NarrationResponse response, boolean accepted) {
  }

  public record NarrationDescriptor(String id, String script) {
  }
}
