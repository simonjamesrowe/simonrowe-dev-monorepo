package com.simonrowe.favourites;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.summary.SummaryRequestPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * Favouriting a news article asks for an in-depth summary in the background.
 *
 * <p>Only the request is tested here. Generation itself is
 * {@code ArticleSummaryService}'s job and is already covered by its own tests, including
 * the insert-first guard that makes a redelivered request cheap.
 */
@ExtendWith(MockitoExtension.class)
class FavouritesAutoSummaryTest {

  @Mock private FavouriteRepository favouriteRepository;
  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private AggregatedEventRepository eventRepository;
  @Mock private SummaryRequestPublisher publisher;

  private FavouritesService service(final boolean enabled) {
    return new FavouritesService(favouriteRepository, articleRepository,
        eventRepository, publisher, enabled);
  }

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient().when(articleRepository.existsById("art-1"))
        .thenReturn(true);
    org.mockito.Mockito.lenient().when(eventRepository.existsById("evt-1"))
        .thenReturn(true);
  }

  @Test
  void favouritingNewsRequestsSummary() {
    when(favouriteRepository.existsByTypeAndContentId(FavouriteType.NEWS, "art-1"))
        .thenReturn(false);

    service(true).add(FavouriteType.NEWS, "art-1");

    verify(publisher).publish("art-1");
  }

  /** Events are not summarised — the button never appears on them either. */
  @Test
  void favouritingAnEventRequestsNothing() {
    when(favouriteRepository.existsByTypeAndContentId(FavouriteType.EVENT, "evt-1"))
        .thenReturn(false);

    service(true).add(FavouriteType.EVENT, "evt-1");

    verify(publisher, never()).publish(anyString());
  }

  /** Re-favouriting something already favourited must not cost another model call. */
  @Test
  void refavouritingRequestsNothing() {
    when(favouriteRepository.existsByTypeAndContentId(FavouriteType.NEWS, "art-1"))
        .thenReturn(true);

    service(true).add(FavouriteType.NEWS, "art-1");

    verify(publisher, never()).publish(anyString());
  }

  /** A concurrent insert of the same favourite is a no-op, so it asks for nothing. */
  @Test
  void losingTheInsertRaceRequestsNothing() {
    when(favouriteRepository.existsByTypeAndContentId(FavouriteType.NEWS, "art-1"))
        .thenReturn(false);
    when(favouriteRepository.insert(any(Favourite.class)))
        .thenThrow(new DuplicateKeyException("already favourited"));

    service(true).add(FavouriteType.NEWS, "art-1");

    verify(publisher, never()).publish(anyString());
  }

  @Test
  void theFlagTurnsItOff() {
    when(favouriteRepository.existsByTypeAndContentId(FavouriteType.NEWS, "art-1"))
        .thenReturn(false);

    service(false).add(FavouriteType.NEWS, "art-1");

    verify(publisher, never()).publish(anyString());
  }

  /**
   * The summary is a nice-to-have on top of favouriting. A broker that is down must not
   * stop the heart from filling.
   */
  @Test
  void favouritingStillSucceedsWhenTheBrokerIsUnreachable() {
    when(favouriteRepository.existsByTypeAndContentId(FavouriteType.NEWS, "art-1"))
        .thenReturn(false);
    doThrow(new IllegalStateException("broker down")).when(publisher).publish("art-1");

    assertThatCode(() -> service(true).add(FavouriteType.NEWS, "art-1"))
        .doesNotThrowAnyException();

    verify(favouriteRepository).insert(any(Favourite.class));
  }
}
