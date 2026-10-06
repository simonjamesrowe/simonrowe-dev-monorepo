package com.simonrowe.aggregation;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.simonrowe.common.LogSafe;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Hides, shows and deletes aggregated articles and events for the admin console, one at a
 * time or in bulk, and tells the search index and the vector store about every change.
 *
 * <p>Publishing is the point. Before this, hiding or deleting an article changed Mongo
 * only, so the article stayed in the site search index and in the chat assistant's vector
 * store, and both kept surfacing content an administrator had deliberately taken down.
 * Both consumers already treat an {@code UPDATED} event for a hidden item as "remove it",
 * so the same event re-adds an item that is shown again.
 *
 * <p>A publish failure never fails the request. The Mongo change has already happened and
 * is the one the administrator asked for; failing the request would report an action as
 * not done when it was, and invite a retry that changes nothing. The stale index entry is
 * cleared by the next full search sync or embedding sync.
 */
@Service
public class AggregatedContentAdminService {

  /**
   * The most ids one bulk request may name. Comfortably above the largest admin page
   * (100), and small enough that one request's publish loop stays short.
   */
  public static final int MAX_BULK_IDS = 200;

  private static final Logger LOG = LoggerFactory.getLogger(AggregatedContentAdminService.class);

  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;
  private final MongoTemplate mongoTemplate;
  private final ContentChangePublisher publisher;

  public AggregatedContentAdminService(
      final AggregatedArticleRepository articleRepository,
      final AggregatedEventRepository eventRepository,
      final MongoTemplate mongoTemplate,
      final ContentChangePublisher publisher) {
    this.articleRepository = articleRepository;
    this.eventRepository = eventRepository;
    this.mongoTemplate = mongoTemplate;
    this.publisher = publisher;
  }

  /**
   * Sets one article's visibility.
   *
   * @param id the article id
   * @param visible the new visibility
   * @return the updated article, empty when there is no such article
   */
  public Optional<AggregatedArticle> setArticleVisibility(final String id, final boolean visible) {
    return articleRepository.findById(id).map(article -> {
      AggregatedArticle saved = articleRepository.save(new AggregatedArticle(
          article.id(), article.title(), article.sourceName(),
          article.sourceUrl(), article.originalUrl(), article.summary(),
          article.fullContent(), article.author(), article.publishedDate(),
          article.fetchedAt(), visible, article.imageUrl()));
      publishUpdated(ContentType.AGGREGATED_ARTICLE, saved.id());
      return saved;
    });
  }

  /**
   * Deletes one article.
   *
   * <p>Publishes the delete even when no article had that id. Removal from the indexes is
   * idempotent, and an id that is gone from Mongo but still indexed (deleted before this
   * published anything) is exactly the case it cleans up.
   *
   * @param id the article id
   */
  public void deleteArticle(final String id) {
    articleRepository.deleteById(id);
    publishDeleted(ContentType.AGGREGATED_ARTICLE, id);
  }

  /**
   * Sets one event's visibility.
   *
   * @param id the event id
   * @param visible the new visibility
   * @return the updated event, empty when there is no such event
   */
  public Optional<AggregatedEvent> setEventVisibility(final String id, final boolean visible) {
    return eventRepository.findById(id).map(event -> {
      AggregatedEvent saved = eventRepository.save(new AggregatedEvent(
          event.id(), event.title(), event.sourceName(),
          event.originalUrl(), event.summary(), event.description(),
          event.eventDate(), event.eventEndDate(), event.venue(),
          event.location(), event.fetchedAt(), visible));
      publishUpdated(ContentType.AGGREGATED_EVENT, saved.id());
      return saved;
    });
  }

  /**
   * Deletes one event, publishing the delete whether or not it existed; see
   * {@link #deleteArticle(String)}.
   *
   * @param id the event id
   */
  public void deleteEvent(final String id) {
    eventRepository.deleteById(id);
    publishDeleted(ContentType.AGGREGATED_EVENT, id);
  }

  /**
   * Applies one action to many articles.
   *
   * @param request the ids and action
   * @return what was done
   * @throws IllegalArgumentException when the request names no ids, too many ids, or an
   *     unknown action
   */
  public BulkActionResult bulkArticles(final BulkActionRequest request) {
    return bulk(request, AggregatedArticle.class, ContentType.AGGREGATED_ARTICLE);
  }

  /**
   * Applies one action to many events.
   *
   * @param request the ids and action
   * @return what was done
   * @throws IllegalArgumentException when the request names no ids, too many ids, or an
   *     unknown action
   */
  public BulkActionResult bulkEvents(final BulkActionRequest request) {
    return bulk(request, AggregatedEvent.class, ContentType.AGGREGATED_EVENT);
  }

  /**
   * Applies the action in one Mongo write, then publishes one event per item it touched.
   *
   * <p>The ids that exist are read first, so the result can say which ones did not and the
   * publish loop covers exactly the items that changed. Unlike the single delete, a bulk
   * delete publishes only for ids it found: the response reports the rest as not found,
   * and publishing for them would contradict it.
   */
  private BulkActionResult bulk(
      final BulkActionRequest request, final Class<?> type, final ContentType contentType) {
    if (request == null) {
      throw new IllegalArgumentException("A request body is required");
    }
    BulkAction action = BulkAction.parse(request.action());
    List<String> requested = requestedIds(request.ids());

    Query byRequestedId = Query.query(Criteria.where("id").in(requested));
    List<String> found = mongoTemplate.query(type)
        .as(IdOnly.class)
        .matching(byRequestedId)
        .all()
        .stream()
        .map(IdOnly::id)
        .toList();
    Set<String> foundSet = new HashSet<>(found);
    List<String> notFound = requested.stream().filter(id -> !foundSet.contains(id)).toList();

    long updated = 0;
    if (!found.isEmpty()) {
      Query byFoundId = Query.query(Criteria.where("id").in(found));
      if (action == BulkAction.DELETE) {
        DeleteResult result = mongoTemplate.remove(byFoundId, type);
        updated = result.getDeletedCount();
        found.forEach(id -> publishDeleted(contentType, id));
      } else {
        UpdateResult result = mongoTemplate.updateMulti(
            byFoundId, Update.update("visible", action == BulkAction.SHOW), type);
        updated = result.getMatchedCount();
        found.forEach(id -> publishUpdated(contentType, id));
      }
    }

    LOG.info("Bulk {} on {}: requested={}, updated={}, notFound={}",
        action, contentType, requested.size(), updated, notFound.size());
    return new BulkActionResult(
        action.name().toLowerCase(Locale.ROOT), requested.size(), updated, notFound.size(),
        notFound);
  }

  /**
   * The distinct, non-blank ids, in request order.
   *
   * @throws IllegalArgumentException when there are none, or more than
   *     {@link #MAX_BULK_IDS}
   */
  private static List<String> requestedIds(final List<String> ids) {
    List<String> distinct = ids == null
        ? List.of()
        : ids.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    if (distinct.isEmpty()) {
      throw new IllegalArgumentException("ids must name at least one item");
    }
    if (distinct.size() > MAX_BULK_IDS) {
      throw new IllegalArgumentException(
          "ids may name at most %d items".formatted(MAX_BULK_IDS));
    }
    return distinct;
  }

  private void publishUpdated(final ContentType contentType, final String id) {
    try {
      publisher.publishUpdated(contentType, id);
    } catch (final RuntimeException e) {
      LOG.warn("Could not publish update for {} {}; the indexes stay stale until the next sync",
          contentType, LogSafe.value(id), e);
    }
  }

  private void publishDeleted(final ContentType contentType, final String id) {
    try {
      publisher.publishDeleted(contentType, id);
    } catch (final RuntimeException e) {
      LOG.warn("Could not publish delete for {} {}; the indexes stay stale until the next sync",
          contentType, LogSafe.value(id), e);
    }
  }

  /** Reads only the id of each matching document, not its scraped body. */
  record IdOnly(String id) {
  }
}
