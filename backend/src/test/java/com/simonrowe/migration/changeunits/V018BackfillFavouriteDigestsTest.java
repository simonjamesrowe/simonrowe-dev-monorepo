package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.agents.WeeklyDigestAgent;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V018BackfillFavouriteDigestsTest {

  private static final String URL_ONE = "https://infoq.com/spring-boot-4";
  private static final String URL_TWO = "https://pg.org/pg19";

  @Mock private FavouriteRepository favouriteRepository;
  @Mock private BlogRepository blogRepository;
  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private WeeklyDigestAgent digestAgent;

  private V018BackfillFavouriteDigests changeUnit;

  @BeforeEach
  void setUp() {
    changeUnit = new V018BackfillFavouriteDigests();
  }

  private static Instant lastWeek(final int dayOffset) {
    return LocalDate.now(ZoneOffset.UTC)
        .with(java.time.DayOfWeek.MONDAY)
        .minusDays(7 - dayOffset)
        .atStartOfDay(ZoneOffset.UTC)
        .plusHours(10)
        .toInstant();
  }

  private static Favourite favourite(final String id, final Instant at) {
    return new Favourite("fav-" + id, FavouriteType.NEWS, id, at);
  }

  private static AggregatedArticle article(final String id, final String url) {
    return new AggregatedArticle(
        id, "Title " + id, "Source", "https://src.com", url,
        "Summary.", "Content.", null,
        Instant.now(), Instant.now(), true, null);
  }

  private static Blog digest(
      final String id, final Instant created, final String content) {
    return new Blog(id, "Digest " + id, "Desc", content, true, null,
        created, created, List.of(), List.of(), BlogContentType.DIGEST);
  }

  @Test
  void doesNothingWhenThereAreNoFavourites() {
    when(favouriteRepository.findByType(FavouriteType.NEWS))
        .thenReturn(List.of());

    changeUnit.execution(favouriteRepository, blogRepository,
        articleRepository, digestAgent);

    verify(digestAgent, never()).generateForWindow(any(), any(), any());
    verify(blogRepository, never()).delete(any());
  }

  @Test
  void skipsTheCurrentWeekAndLeavesItToTheScheduledRun() {
    Instant today = Instant.now();
    when(favouriteRepository.findByType(FavouriteType.NEWS))
        .thenReturn(List.of(favourite("art-1", today)));

    changeUnit.execution(favouriteRepository, blogRepository,
        articleRepository, digestAgent);

    verify(digestAgent, never()).generateForWindow(any(), any(), any());
  }

  @Test
  void generatesOneDigestPerHistoricalWeekAndStampsTheFollowingMonday() {
    Instant hearted = lastWeek(2);
    when(favouriteRepository.findByType(FavouriteType.NEWS))
        .thenReturn(List.of(favourite("art-1", hearted)));
    lenient().when(articleRepository.findById("art-1"))
        .thenReturn(Optional.of(article("art-1", URL_ONE)));
    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of());
    when(digestAgent.generateForWindow(any(), any(), any()))
        .thenReturn(Optional.of(digest("new", hearted, "### x")));

    changeUnit.execution(favouriteRepository, blogRepository,
        articleRepository, digestAgent);

    ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> publishAt = ArgumentCaptor.forClass(Instant.class);
    verify(digestAgent)
        .generateForWindow(from.capture(), to.capture(), publishAt.capture());

    LocalDate weekStart = hearted.atZone(ZoneOffset.UTC).toLocalDate()
        .with(java.time.DayOfWeek.MONDAY);
    assertThat(from.getValue())
        .isEqualTo(weekStart.atStartOfDay(ZoneOffset.UTC).toInstant());
    assertThat(publishAt.getValue()).isEqualTo(weekStart.plusDays(7)
        .atStartOfDay(ZoneOffset.UTC).plusHours(8).toInstant());
    assertThat(to.getValue()).isBefore(publishAt.getValue());
  }

  @Test
  void replacesAnOldStyleDigestForThatWeek() {
    Instant hearted = lastWeek(2);
    LocalDate weekStart = hearted.atZone(ZoneOffset.UTC).toLocalDate()
        .with(java.time.DayOfWeek.MONDAY);
    Instant oldPublishedAt = weekStart.plusDays(7)
        .atStartOfDay(ZoneOffset.UTC).plusHours(8).toInstant();
    Blog oldDigest = digest("old", oldPublishedAt, "a link roundup");

    when(favouriteRepository.findByType(FavouriteType.NEWS))
        .thenReturn(List.of(favourite("art-1", hearted)));
    when(articleRepository.findById("art-1"))
        .thenReturn(Optional.of(article("art-1", URL_ONE)));
    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(oldDigest));
    when(digestAgent.generateForWindow(any(), any(), any()))
        .thenReturn(Optional.of(digest("new", oldPublishedAt, "### new")));

    changeUnit.execution(favouriteRepository, blogRepository,
        articleRepository, digestAgent);

    verify(blogRepository).delete(oldDigest);
  }

  @Test
  void doesNotDeleteThePreviousWeeksDigest() {
    // A digest published ON the Monday that starts this week covers the week
    // BEFORE it, so it must survive untouched. Getting this wrong made one
    // backfilled week delete two posts against real data.
    Instant hearted = lastWeek(2);
    LocalDate weekStart = hearted.atZone(ZoneOffset.UTC).toLocalDate()
        .with(java.time.DayOfWeek.MONDAY);
    Blog previousWeeksDigest = digest("previous",
        weekStart.atStartOfDay(ZoneOffset.UTC).plusHours(8).toInstant(),
        "the week before's roundup");
    Blog thisWeeksDigest = digest("this", weekStart.plusDays(7)
        .atStartOfDay(ZoneOffset.UTC).plusHours(8).toInstant(), "roundup");

    when(favouriteRepository.findByType(FavouriteType.NEWS))
        .thenReturn(List.of(favourite("art-1", hearted)));
    when(articleRepository.findById("art-1"))
        .thenReturn(Optional.of(article("art-1", URL_ONE)));
    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(previousWeeksDigest, thisWeeksDigest));
    when(digestAgent.generateForWindow(any(), any(), any()))
        .thenReturn(Optional.of(digest("new", hearted, "### new")));

    changeUnit.execution(favouriteRepository, blogRepository,
        articleRepository, digestAgent);

    verify(blogRepository).delete(thisWeeksDigest);
    verify(blogRepository, never()).delete(previousWeeksDigest);
  }

  @Test
  void keepsTheOldDigestWhenGenerationProducesNothing() {
    Instant hearted = lastWeek(2);
    LocalDate weekStart = hearted.atZone(ZoneOffset.UTC).toLocalDate()
        .with(java.time.DayOfWeek.MONDAY);
    Blog oldDigest = digest("old", weekStart.plusDays(7)
        .atStartOfDay(ZoneOffset.UTC).plusHours(8).toInstant(), "roundup");

    when(favouriteRepository.findByType(FavouriteType.NEWS))
        .thenReturn(List.of(favourite("art-1", hearted)));
    when(articleRepository.findById("art-1"))
        .thenReturn(Optional.of(article("art-1", URL_ONE)));
    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(oldDigest));
    when(digestAgent.generateForWindow(any(), any(), any()))
        .thenReturn(Optional.empty());

    changeUnit.execution(favouriteRepository, blogRepository,
        articleRepository, digestAgent);

    verify(blogRepository, never()).delete(any());
  }

  @Test
  void isIdempotentWhenTheWeekAlreadyHasFavouritesBasedDigest() {
    Instant hearted = lastWeek(2);
    LocalDate weekStart = hearted.atZone(ZoneOffset.UTC).toLocalDate()
        .with(java.time.DayOfWeek.MONDAY);
    Blog alreadyBackfilled = digest("done", weekStart.plusDays(7)
        .atStartOfDay(ZoneOffset.UTC).plusHours(8).toInstant(),
        "### [Title](" + URL_ONE + ")\n\nbody");

    when(favouriteRepository.findByType(FavouriteType.NEWS))
        .thenReturn(List.of(favourite("art-1", hearted)));
    when(articleRepository.findById("art-1"))
        .thenReturn(Optional.of(article("art-1", URL_ONE)));
    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(alreadyBackfilled));

    changeUnit.execution(favouriteRepository, blogRepository,
        articleRepository, digestAgent);

    verify(digestAgent, never()).generateForWindow(any(), any(), any());
    verify(blogRepository, never()).delete(any());
  }

  @Test
  void regeneratesWhenTheExistingDigestCoversOnlySomeOfTheWeeksFavourites() {
    Instant hearted = lastWeek(2);
    LocalDate weekStart = hearted.atZone(ZoneOffset.UTC).toLocalDate()
        .with(java.time.DayOfWeek.MONDAY);
    Blog partial = digest("partial", weekStart.plusDays(7)
        .atStartOfDay(ZoneOffset.UTC).plusHours(8).toInstant(),
        "### [Title](" + URL_ONE + ")\n\nbody");

    when(favouriteRepository.findByType(FavouriteType.NEWS))
        .thenReturn(List.of(
            favourite("art-1", hearted), favourite("art-2", hearted)));
    when(articleRepository.findById("art-1"))
        .thenReturn(Optional.of(article("art-1", URL_ONE)));
    when(articleRepository.findById("art-2"))
        .thenReturn(Optional.of(article("art-2", URL_TWO)));
    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(partial));
    when(digestAgent.generateForWindow(any(), any(), any()))
        .thenReturn(Optional.of(digest("new", hearted, "### both")));

    changeUnit.execution(favouriteRepository, blogRepository,
        articleRepository, digestAgent);

    verify(digestAgent).generateForWindow(any(), any(), any());
    verify(blogRepository).delete(partial);
  }

  @Test
  void oneFailingWeekDoesNotAbortTheRest() {
    Instant weekOne = lastWeek(2);
    Instant weekTwo = weekOne.minusSeconds(7 * 86_400);

    when(favouriteRepository.findByType(FavouriteType.NEWS))
        .thenReturn(List.of(
            favourite("art-1", weekOne), favourite("art-2", weekTwo)));
    lenient().when(articleRepository.findById(any()))
        .thenReturn(Optional.empty());
    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of());
    when(digestAgent.generateForWindow(any(), any(), any()))
        .thenThrow(new RuntimeException("LLM exploded"))
        .thenReturn(Optional.of(digest("new", weekOne, "### x")));

    changeUnit.execution(favouriteRepository, blogRepository,
        articleRepository, digestAgent);

    verify(digestAgent, org.mockito.Mockito.times(2))
        .generateForWindow(any(), any(), any());
  }
}
