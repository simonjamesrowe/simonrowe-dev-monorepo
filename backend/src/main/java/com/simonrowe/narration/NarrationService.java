package com.simonrowe.narration;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * The content-agnostic half of the narration pipeline: queueing, claiming, reuse,
 * invalidation and the long-poll status read.
 *
 * <p>Everything that knows what is being narrated lives behind {@link NarrationSource},
 * resolved here from a registry keyed by {@link NarrationContentType}. Renamed from
 * {@code BlogNarrationService} when article summaries became the second kind of content;
 * the blog contract at {@code /api/blogs/{blogId}/narration} is unchanged.
 */
@Service
public class NarrationService {

  private static final String AUDIO_ENCODING = "MP3";
  private static final Duration STATUS_POLL_INTERVAL = Duration.ofMillis(500);

  private final NarrationRepository narrationRepository;
  private final NarrationProperties properties;
  private final NarrationProvider provider;
  private final NarrationRequestPublisher publisher;
  private final NarrationStorage storage;
  private final MongoTemplate mongoTemplate;
  private final MeterRegistry meterRegistry;
  private final Map<NarrationContentType, NarrationSource> sources =
      new EnumMap<>(NarrationContentType.class);

  public NarrationService(
      final NarrationRepository narrationRepository,
      final NarrationProperties properties,
      final NarrationProvider provider,
      final NarrationRequestPublisher publisher,
      final NarrationStorage storage,
      final MongoTemplate mongoTemplate,
      final MeterRegistry meterRegistry,
      final List<NarrationSource> narrationSources
  ) {
    this.narrationRepository = narrationRepository;
    this.properties = properties;
    this.provider = provider;
    this.publisher = publisher;
    this.storage = storage;
    this.mongoTemplate = mongoTemplate;
    this.meterRegistry = meterRegistry;
    for (NarrationSource source : narrationSources) {
      NarrationSource previous = sources.put(source.contentType(), source);
      if (previous != null) {
        throw new IllegalStateException(
            "Two NarrationSource beans claim " + source.contentType());
      }
    }
  }

  /**
   * Current narration state, optionally waiting for it to change.
   *
   * @param contentType what kind of content this is
   * @param contentId the content id
   * @param afterVersion the version the client already has, or null for an immediate read
   * @param waitSeconds how long to hold the request open
   * @return the current state
   */
  public NarrationResponse getStatus(
      final NarrationContentType contentType,
      final String contentId,
      final Long afterVersion,
      final int waitSeconds
  ) {
    NarrationSource source = sourceFor(contentType);
    Instant deadline = Instant.now().plusSeconds(waitSeconds);
    NarrationResponse response;
    do {
      response = currentResponse(source, contentId);
      if (afterVersion == null || response.version() != afterVersion
          || response.isTerminal() || waitSeconds == 0) {
        return response;
      }
      sleepUntilNextPoll(deadline);
    } while (Instant.now().isBefore(deadline));
    return currentResponse(source, contentId);
  }

  /**
   * Queues generation, or reuses an existing narration.
   *
   * @param contentType what kind of content this is
   * @param contentId the content id
   * @return the outcome, with {@code accepted} set when new work was queued
   */
  public RequestResult request(
      final NarrationContentType contentType,
      final String contentId
  ) {
    NarrationSource source = sourceFor(contentType);
    NarrationSource.NarrationDescriptor descriptor = source.scriptFor(contentId);
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
        contentType,
        contentId,
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

  /**
   * Every item of a content type that has playable audio right now — one row per content id,
   * the newest {@code READY} one.
   *
   * <p>The reduction has to happen in Mongo rather than in Java because narrations are
   * fingerprint-addressed: the {@code _id} is a hash over the script text and the voice
   * settings, so a single {@code contentId} legitimately accumulates several documents over its
   * lifetime, and superseded ones are marked {@code STALE} rather than deleted. Loading them all
   * to keep one per id would ship every stale sibling over the wire to reimplement a
   * {@code $group} in application code.
   *
   * <p>{@code status = READY} in the match stage is the whole of the "never advertise something
   * unplayable" guarantee: {@code STALE}, {@code FAILED}, {@code UNCERTAIN}, {@code QUEUED} and
   * {@code PROCESSING} rows cannot reach the output. Note that a content id whose <em>newest</em>
   * row is {@code STALE} still returns its older {@code READY} row, which is correct — that MP3
   * is still on disk and still playable, and offering it beats pretending nothing exists.
   *
   * <p>{@code idx_narration_content_updated}
   * ({@code {contentType: 1, contentId: 1, updatedAt: -1}}, declared on {@link Narration})
   * already supports the {@code contentType} equality plus the {@code updatedAt} ordering, so
   * this adds <strong>no index and therefore no Mongock change unit</strong>.
   *
   * @param contentType which kind of content to list
   * @return the ready narrations, empty when nothing of this type has audio yet
   */
  public List<ReadyNarration> readyNarrations(final NarrationContentType contentType) {
    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("contentType").is(contentType)
            .and("status").is(NarrationStatus.READY)),
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "updatedAt")),
        Aggregation.group("contentId")
            .first("audioPath").as("audioUrl")
            .first("durationSeconds").as("durationSeconds"),
        Aggregation.project("audioUrl", "durationSeconds").and("_id").as("contentId"));

    return mongoTemplate
        .aggregate(aggregation, Narration.class, ReadyNarration.class)
        .getMappedResults()
        .stream()
        .filter(ready -> ready.contentId() != null && ready.audioUrl() != null)
        .toList();
  }

  /**
   * Takes exclusive ownership of a queued narration for the duration of a lease.
   *
   * @param narrationId the narration to claim
   * @param now the current instant
   * @return the claimed narration, or empty when another worker got there first
   */
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

  /**
   * The script and fingerprint for a piece of content, via its source.
   *
   * @param contentType what kind of content this is
   * @param contentId the content id
   * @return the descriptor
   */
  public NarrationSource.NarrationDescriptor descriptor(
      final NarrationContentType contentType,
      final String contentId
  ) {
    return sourceFor(contentType).scriptFor(contentId);
  }

  /**
   * Whether a narration is still audio of its content's current text.
   *
   * @param narration the narration to check
   * @return true when it is still current
   */
  public boolean isCurrentAndPublished(final Narration narration) {
    NarrationSource source = sources.get(narration.contentType());
    return source != null && source.isCurrent(narration);
  }

  /**
   * Marks every narration for a piece of content stale except the one matching its current
   * text, deleting their audio.
   *
   * @param contentType what kind of content this is
   * @param contentId the content id
   */
  public void invalidate(
      final NarrationContentType contentType,
      final String contentId
  ) {
    String currentId = currentDescriptorId(contentType, contentId);
    for (Narration narration
        : narrationRepository.findByContentTypeAndContentId(contentType, contentId)) {
      if (!narration.id().equals(currentId)) {
        storage.delete(narration);
        narration.markStale(Instant.now());
        narrationRepository.save(narration);
      }
    }
  }

  private String currentDescriptorId(
      final NarrationContentType contentType,
      final String contentId
  ) {
    NarrationSource source = sources.get(contentType);
    if (source == null) {
      return null;
    }
    try {
      return source.scriptFor(contentId).id();
    } catch (RuntimeException ex) {
      // The content is gone, unpublished or no longer narratable — so nothing is current
      // and every existing narration for it is stale.
      return null;
    }
  }

  private NarrationResponse currentResponse(
      final NarrationSource source,
      final String contentId
  ) {
    NarrationSource.NarrationDescriptor descriptor = source.scriptFor(contentId);
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

  private NarrationSource sourceFor(final NarrationContentType contentType) {
    NarrationSource source = sources.get(contentType);
    if (source == null) {
      throw new IllegalStateException("No NarrationSource for " + contentType);
    }
    return source;
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

  /**
   * The outcome of a narration request.
   *
   * @param response the current state
   * @param accepted true when new work was queued, surfaced as a 202
   */
  public record RequestResult(NarrationResponse response, boolean accepted) {
  }
}
