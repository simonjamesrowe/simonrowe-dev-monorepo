package com.simonrowe.playlist;

import com.mongodb.client.result.UpdateResult;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.narration.NarrationResponse;
import com.simonrowe.narration.NarrationService;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryReadyEvent;
import com.simonrowe.summary.ArticleSummaryService;
import com.simonrowe.summary.SummaryRequestPublisher;
import com.simonrowe.summary.SummaryStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Changes to the shared playlist.
 *
 * <p>There is one playlist for the whole site, like favourites. Anyone can read it and play
 * it; only a signed-in caller can change it ({@code SecurityConfig}). Its position and
 * "listened" state are therefore shared too: they are only written by a signed-in listener,
 * so a visitor playing it does not move anyone's place.
 *
 * <p>Writes go through {@link MongoTemplate} updates rather than load-modify-save, so two
 * devices saving progress at once cannot undo each other's other changes.
 */
@Service
public class PlaylistService implements PlaylistRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(PlaylistService.class);

  private static final String ID = "_id";
  private static final String STATUS = "status";
  private static final String PINNED_AT = "pinnedAt";
  private static final String PROGRESS = "progressSeconds";
  private static final String UPDATED_AT = "updatedAt";
  private static final String LISTENED_AT = "listenedAt";
  private static final String VOICE_REQUESTED = "voiceRequested";

  private final MongoTemplate mongoTemplate;
  private final AggregatedArticleRepository articleRepository;
  private final BlogRepository blogRepository;
  private final ArticleSummaryService summaryService;
  private final SummaryRequestPublisher summaryRequestPublisher;
  private final NarrationService narrationService;

  public PlaylistService(
      final MongoTemplate mongoTemplate,
      final AggregatedArticleRepository articleRepository,
      final BlogRepository blogRepository,
      final ArticleSummaryService summaryService,
      final SummaryRequestPublisher summaryRequestPublisher,
      final NarrationService narrationService
  ) {
    this.mongoTemplate = mongoTemplate;
    this.articleRepository = articleRepository;
    this.blogRepository = blogRepository;
    this.summaryService = summaryService;
    this.summaryRequestPublisher = summaryRequestPublisher;
    this.narrationService = narrationService;
  }

  @Override
  public void record(
      final PlaylistContentType contentType,
      final String contentId,
      final PlaylistSource source
  ) {
    try {
      if (!contentExists(contentType, contentId)) {
        return;
      }
      if (insertIfAbsent(contentType, contentId, source)) {
        LOG.info("Added to playlist: {} {} via {}", contentType, contentId, source);
        voiceIfAutomatic(contentType, contentId);
      }
    } catch (RuntimeException ex) {
      LOG.warn("Could not add to playlist: {} {} via {}", contentType, contentId, source, ex);
    }
  }

  /**
   * Puts content on the playlist by hand. Something already queued stays as it is. Something
   * already heard is queued again from the start, which is "queue again"; it takes its place by
   * date like everything else.
   *
   * @param contentType what kind of content
   * @param contentId its id
   * @throws ResponseStatusException 404 when the content does not exist or is not public
   */
  public void add(final PlaylistContentType contentType, final String contentId) {
    if (!contentExists(contentType, contentId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found");
    }
    String id = PlaylistItem.idFor(contentType, contentId);
    Instant now = Instant.now();
    Query notQueued = Query.query(Criteria.where(ID).is(id)
        .and(STATUS).ne(PlaylistItemStatus.QUEUED));
    Update requeue = new Update()
        .set(STATUS, PlaylistItemStatus.QUEUED)
        .set(PROGRESS, 0.0)
        .set(UPDATED_AT, now)
        .unset(LISTENED_AT);
    if (mongoTemplate.updateFirst(notQueued, requeue, PlaylistItem.class).getMatchedCount() > 0) {
      return;
    }
    if (insertIfAbsent(contentType, contentId, PlaylistSource.ADDED)) {
      voiceIfAutomatic(contentType, contentId);
    }
  }

  /**
   * Takes an item off the playlist. Removing something absent is not an error.
   *
   * @param contentType what kind of content
   * @param contentId its id
   */
  public void remove(final PlaylistContentType contentType, final String contentId) {
    mongoTemplate.remove(byId(contentType, contentId), PlaylistItem.class);
  }

  /**
   * Remembers how far into an item the listener got, so it resumes there on any device.
   * Ignored for anything not queued, so a late save cannot undo "listened".
   *
   * @param contentType what kind of content
   * @param contentId its id
   * @param positionSeconds seconds into the audio
   */
  public void saveProgress(
      final PlaylistContentType contentType,
      final String contentId,
      final double positionSeconds
  ) {
    Query query = Query.query(Criteria.where(ID).is(PlaylistItem.idFor(contentType, contentId))
        .and(STATUS).is(PlaylistItemStatus.QUEUED));
    mongoTemplate.updateFirst(query,
        new Update().set(PROGRESS, positionSeconds).set(UPDATED_AT, Instant.now()),
        PlaylistItem.class);
  }

  /**
   * Records that an item was played to the end. It leaves the queue, and the TTL index on
   * {@code listenedAt} removes it 30 days later.
   *
   * @param contentType what kind of content
   * @param contentId its id
   */
  public void markListened(final PlaylistContentType contentType, final String contentId) {
    Instant now = Instant.now();
    mongoTemplate.updateFirst(byId(contentType, contentId), new Update()
            .set(STATUS, PlaylistItemStatus.LISTENED)
            .set(LISTENED_AT, now)
            .set(PROGRESS, 0.0)
            .set(UPDATED_AT, now)
            .unset(PINNED_AT),
        PlaylistItem.class);
  }

  /**
   * Puts a queued item at the front, ahead of the date order. The latest item chosen comes
   * first. The pin goes when the item is heard or removed. Ignored for anything not queued.
   *
   * @param contentType what kind of content
   * @param contentId its id
   */
  public void playNext(final PlaylistContentType contentType, final String contentId) {
    Instant now = Instant.now();
    Query query = Query.query(Criteria.where(ID).is(PlaylistItem.idFor(contentType, contentId))
        .and(STATUS).is(PlaylistItemStatus.QUEUED));
    mongoTemplate.updateFirst(query,
        new Update().set(PINNED_AT, now).set(UPDATED_AT, now), PlaylistItem.class);
  }

  /** Removes everything already heard. */
  public void clearListened() {
    mongoTemplate.remove(Query.query(Criteria.where(STATUS).is(PlaylistItemStatus.LISTENED)),
        PlaylistItem.class);
  }

  /**
   * The playlist's setting.
   *
   * @return the stored setting, or the default when none has been saved
   */
  public PlaylistSettings settings() {
    PlaylistSettings settings =
        mongoTemplate.findById(PlaylistSettings.ID, PlaylistSettings.class);
    return settings == null ? PlaylistSettings.defaults() : settings;
  }

  /**
   * Turns automatic audio for new summaries on or off. Items already waiting are left alone.
   *
   * @param autoVoice the new value
   * @return the saved setting
   */
  public PlaylistSettings updateSettings(final boolean autoVoice) {
    return mongoTemplate.save(new PlaylistSettings(PlaylistSettings.ID, autoVoice));
  }

  /**
   * Makes audio for queued items, in one request.
   *
   * <p>One request rather than one per item because the per-item narration endpoint shares a
   * five-a-minute rate limit with summaries, so a list of five would 429 on the fifth. This
   * endpoint draws a single token from that bucket instead; the monthly text-to-speech budget
   * still applies to every item.
   *
   * <p>An item whose summary is not written yet gets one requested and is marked
   * {@code voiceRequested}; {@link #onSummaryReady} makes its audio when the summary arrives.
   * Nothing moves: an item takes its place by date once it has audio.
   *
   * @param items the items, duplicates ignored
   * @return what happened to each distinct item, in the order asked
   */
  public List<PlaylistRequests.AudioResult> requestAudio(final List<PlaylistItemKey> items) {
    Map<String, PlaylistItemKey> distinct = new LinkedHashMap<>();
    items.forEach(key -> distinct.putIfAbsent(key.itemId(), key));

    List<PlaylistRequests.AudioResult> results = new ArrayList<>(distinct.size());
    for (PlaylistItemKey key : distinct.values()) {
      PlaylistItem item = mongoTemplate.findById(key.itemId(), PlaylistItem.class);
      PlaylistAudioOutcome outcome;
      if (item == null || item.status() != PlaylistItemStatus.QUEUED) {
        outcome = PlaylistAudioOutcome.NOT_IN_PLAYLIST;
      } else {
        outcome = voice(key.contentType(), key.contentId(), true);
        if (outcome != PlaylistAudioOutcome.INELIGIBLE) {
          mongoTemplate.updateFirst(byId(key.contentType(), key.contentId()), new Update()
                  .set(VOICE_REQUESTED, true)
                  .set(UPDATED_AT, Instant.now()),
              PlaylistItem.class);
        }
      }
      results.add(new PlaylistRequests.AudioResult(
          key.contentType(), key.contentId(), outcome));
    }
    return results;
  }

  /**
   * Makes audio for a queued item whose summary has just been written, when someone asked for
   * it or automatic audio is on.
   *
   * <p>Runs on the thread that wrote the summary, the request thread or the summary consumer.
   * It never throws: the summary is already saved, and that must stand.
   *
   * @param event the summary that is ready
   */
  @EventListener
  public void onSummaryReady(final ArticleSummaryReadyEvent event) {
    try {
      PlaylistItem item = mongoTemplate.findById(
          PlaylistItem.idFor(PlaylistContentType.ARTICLE_SUMMARY, event.articleId()),
          PlaylistItem.class);
      if (item == null || item.status() != PlaylistItemStatus.QUEUED) {
        return;
      }
      if (!item.voiceRequested() && !settings().autoVoice()) {
        return;
      }
      PlaylistAudioOutcome outcome =
          voice(PlaylistContentType.ARTICLE_SUMMARY, event.articleId(), false);
      LOG.info("Playlist audio after summary: articleId={}, outcome={}",
          event.articleId(), outcome);
    } catch (RuntimeException ex) {
      LOG.warn("Could not make playlist audio after summary: articleId={}",
          event.articleId(), ex);
    }
  }

  private void voiceIfAutomatic(
      final PlaylistContentType contentType,
      final String contentId
  ) {
    if (settings().autoVoice()) {
      // Never asks for a summary: the action that recorded this item already did, or it is
      // a post. onSummaryReady picks up an article whose summary is still being written.
      voice(contentType, contentId, false);
    }
  }

  /**
   * Asks for an item's audio.
   *
   * @param requestSummary whether to ask for a missing summary first; only an explicit
   *     "make audio" does, because only then has somebody chosen to spend on this item
   */
  private PlaylistAudioOutcome voice(
      final PlaylistContentType contentType,
      final String contentId,
      final boolean requestSummary
  ) {
    try {
      if (contentType == PlaylistContentType.ARTICLE_SUMMARY) {
        Optional<ArticleSummary> summary = summaryService.findFor(contentId);
        SummaryStatus status = summary.map(ArticleSummary::status).orElse(null);
        if (status == SummaryStatus.FAILED && !summary.get().retryable()) {
          return PlaylistAudioOutcome.INELIGIBLE;
        }
        if (status != SummaryStatus.READY) {
          if (requestSummary) {
            // Asynchronous: writing a summary takes 15-30 seconds, too long to hold a request
            // open for several. Also restarts one stuck in GENERATING, which request() reclaims.
            summaryRequestPublisher.publish(contentId);
          }
          return PlaylistAudioOutcome.SUMMARISING;
        }
      }
      NarrationResponse response = narrationService
          .request(contentType.narrationType(), contentId)
          .response();
      return outcomeOf(response.state());
    } catch (ResponseStatusException ex) {
      // The narration sources answer 404 for content that is gone or hidden and 413 for a
      // script too long to narrate. Neither will change by asking again.
      return PlaylistAudioOutcome.INELIGIBLE;
    } catch (RuntimeException ex) {
      LOG.warn("Could not request playlist audio: {} {}", contentType, contentId, ex);
      return PlaylistAudioOutcome.FAILED;
    }
  }

  private static PlaylistAudioOutcome outcomeOf(final NarrationResponse.PublicState state) {
    return switch (state) {
      case READY -> PlaylistAudioOutcome.READY;
      case QUEUED -> PlaylistAudioOutcome.QUEUED;
      case PROCESSING -> PlaylistAudioOutcome.PROCESSING;
      case UNAVAILABLE -> PlaylistAudioOutcome.UNAVAILABLE;
      case INELIGIBLE -> PlaylistAudioOutcome.INELIGIBLE;
      case FAILED, NOT_REQUESTED -> PlaylistAudioOutcome.FAILED;
    };
  }

  /**
   * Inserts a queued item unless one exists, in a single upsert keyed on {@code _id}.
   *
   * @return whether this call created it
   */
  private boolean insertIfAbsent(
      final PlaylistContentType contentType,
      final String contentId,
      final PlaylistSource source
  ) {
    Instant now = Instant.now();
    Update update = new Update()
        .setOnInsert("contentType", contentType)
        .setOnInsert("contentId", contentId)
        .setOnInsert(STATUS, PlaylistItemStatus.QUEUED)
        .setOnInsert(PROGRESS, 0.0)
        .setOnInsert("addedVia", source)
        .setOnInsert(VOICE_REQUESTED, false)
        .setOnInsert("addedAt", now)
        .setOnInsert(UPDATED_AT, now);
    try {
      UpdateResult result =
          mongoTemplate.upsert(byId(contentType, contentId), update, PlaylistItem.class);
      return result.getUpsertedId() != null;
    } catch (DuplicateKeyException ex) {
      // Two upserts raced on the same _id and the other one inserted. It is there either way.
      return false;
    }
  }

  private boolean contentExists(
      final PlaylistContentType contentType,
      final String contentId
  ) {
    return switch (contentType) {
      case ARTICLE_SUMMARY -> articleRepository.findById(contentId)
          .filter(AggregatedArticle::visible)
          .isPresent();
      case BLOG -> blogRepository.findByIdAndPublishedTrue(contentId).isPresent();
    };
  }

  private static Query byId(final PlaylistContentType contentType, final String contentId) {
    return Query.query(Criteria.where(ID).is(PlaylistItem.idFor(contentType, contentId)));
  }
}
