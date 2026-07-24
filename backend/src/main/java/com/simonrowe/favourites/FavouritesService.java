package com.simonrowe.favourites;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.aggregation.ArticleResponse;
import com.simonrowe.aggregation.EventResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages the globally shared favourites over aggregated news articles and events.
 * Favourites are not scoped to a user: reads return the same set for everyone and any
 * authenticated user can add or remove a favourite. Favourites reference content by id only.
 */
@Service
public class FavouritesService {

  private static final Logger LOG = LoggerFactory.getLogger(FavouritesService.class);

  private final FavouriteRepository favouriteRepository;
  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;

  public FavouritesService(
      final FavouriteRepository favouriteRepository,
      final AggregatedArticleRepository articleRepository,
      final AggregatedEventRepository eventRepository
  ) {
    this.favouriteRepository = favouriteRepository;
    this.articleRepository = articleRepository;
    this.eventRepository = eventRepository;
  }

  /**
   * Records a favourite. Idempotent: saving an already-favourited item is a no-op.
   *
   * @throws ResponseStatusException 404 when the referenced content does not exist
   */
  public void add(final FavouriteType type, final String contentId) {
    if (!contentExists(type, contentId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found");
    }
    if (favouriteRepository.existsByTypeAndContentId(type, contentId)) {
      return;
    }
    try {
      favouriteRepository.insert(new Favourite(null, type, contentId, Instant.now()));
      LOG.debug("Added favourite: type={}, contentId={}", type, contentId);
    } catch (final DuplicateKeyException e) {
      // Concurrent save of the same item — the favourite already exists, which is fine.
    }
  }

  /** Removes a favourite. Idempotent: removing an absent favourite is a no-op. */
  public void remove(final FavouriteType type, final String contentId) {
    favouriteRepository.deleteByTypeAndContentId(type, contentId);
    LOG.debug("Removed favourite: type={}, contentId={}", type, contentId);
  }

  /** Ids of the content favourited for the given type. */
  public Set<String> getIds(final FavouriteType type) {
    return favouriteRepository.findByType(type).stream()
        .map(Favourite::contentId)
        .collect(Collectors.toSet());
  }

  /**
   * The favourited articles, most recently favourited first. Includes articles regardless
   * of their {@code visible} flag; favourites whose article was deleted are skipped.
   */
  public Page<ArticleResponse> getFavouriteArticles(final Pageable pageable) {
    return getFavouriteContent(FavouriteType.NEWS, pageable,
        articleRepository::findAllById, AggregatedArticle::id, ArticleResponse::from);
  }

  /**
   * The favourited events, most recently favourited first. Includes events regardless of
   * their {@code visible} flag; favourites whose event was deleted are skipped.
   */
  public Page<EventResponse> getFavouriteEvents(final Pageable pageable) {
    return getFavouriteContent(FavouriteType.EVENT, pageable,
        eventRepository::findAllById, AggregatedEvent::id, EventResponse::from);
  }

  private <C, R> Page<R> getFavouriteContent(
      final FavouriteType type,
      final Pageable pageable,
      final Function<List<String>, Iterable<C>> contentLoader,
      final Function<C, String> idExtractor,
      final Function<C, R> responseMapper
  ) {
    final Page<Favourite> favourites =
        favouriteRepository.findByTypeOrderByCreatedAtDesc(type, pageable);
    final List<String> contentIds = favourites.stream()
        .map(Favourite::contentId)
        .toList();
    final Map<String, C> contentById = new HashMap<>();
    contentLoader.apply(contentIds)
        .forEach(content -> contentById.put(idExtractor.apply(content), content));
    final List<R> responses = contentIds.stream()
        .map(contentById::get)
        .filter(Objects::nonNull)
        .map(responseMapper)
        .toList();
    return new PageImpl<>(responses, pageable, favourites.getTotalElements());
  }

  private boolean contentExists(final FavouriteType type, final String contentId) {
    return switch (type) {
      case NEWS -> articleRepository.existsById(contentId);
      case EVENT -> eventRepository.existsById(contentId);
    };
  }
}
