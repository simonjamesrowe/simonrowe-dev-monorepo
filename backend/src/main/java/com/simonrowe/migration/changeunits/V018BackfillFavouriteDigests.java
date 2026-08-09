package com.simonrowe.migration.changeunits;

import com.simonrowe.agents.WeeklyDigestAgent;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backfills one favourites-based digest per historical week, for the weeks that
 * were favourited before the digest was rebuilt around favourites.
 *
 * <p>Weeks run Monday to Sunday and are keyed off {@code Favourite.createdAt}
 * (when an article was hearted). Each week's post is stamped the following
 * Monday at 08:00 UTC, matching the hour the scheduled job would have published
 * it. The current, still-open week is skipped — the scheduled run owns it.
 *
 * <p>Where a week already has an old-style digest (the pre-favourites link
 * roundup), that post is deleted and replaced, which is the requested
 * behaviour: one digest per week, built from favourites.
 *
 * <p>Idempotent. A week is skipped when its existing digest already links every
 * article favourited that week — which is exactly the state this change unit
 * leaves behind, so a replay after a restore regenerates nothing. All LLM,
 * scraping and image work is wrapped so a failure is logged and the remaining
 * weeks still run; a failure here must never abort application boot.
 */
@ChangeUnit(
    id = "backfill-favourite-digests", order = "018", author = "simonrowe")
public class V018BackfillFavouriteDigests {

  private static final Logger log =
      LoggerFactory.getLogger(V018BackfillFavouriteDigests.class);

  /** The hour the scheduled digest publishes, mirrored onto backfilled posts. */
  private static final int PUBLISH_HOUR_UTC = 8;

  @Execution
  public void execution(
      final FavouriteRepository favouriteRepository,
      final BlogRepository blogRepository,
      final AggregatedArticleRepository articleRepository,
      final WeeklyDigestAgent digestAgent) {
    List<Favourite> favourites =
        favouriteRepository.findByType(FavouriteType.NEWS);
    if (favourites.isEmpty()) {
      log.info("No news favourites; nothing to backfill");
      return;
    }

    LocalDate currentWeek = weekStartOf(Instant.now());
    TreeSet<LocalDate> weeks = new TreeSet<>();
    for (Favourite favourite : favourites) {
      if (favourite.createdAt() == null) {
        continue;
      }
      LocalDate week = weekStartOf(favourite.createdAt());
      if (week.isBefore(currentWeek)) {
        weeks.add(week);
      }
    }

    if (weeks.isEmpty()) {
      log.info("All {} news favourite(s) fall in the current week; "
          + "leaving that to the scheduled run", favourites.size());
      return;
    }

    log.info("Backfilling digests for {} historical week(s): {}",
        weeks.size(), weeks);

    int published = 0;
    for (LocalDate week : weeks) {
      if (backfillWeek(week, blogRepository, articleRepository,
          digestAgent, favourites)) {
        published++;
      }
    }
    log.info("Backfill complete: {} digest(s) published across {} week(s)",
        published, weeks.size());
  }

  private boolean backfillWeek(
      final LocalDate weekStart,
      final BlogRepository blogRepository,
      final AggregatedArticleRepository articleRepository,
      final WeeklyDigestAgent digestAgent,
      final List<Favourite> allFavourites) {
    Instant from = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant to = weekStart.plusDays(7)
        .atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);
    Instant publishAt = weekStart.plusDays(7)
        .atStartOfDay(ZoneOffset.UTC).plusHours(PUBLISH_HOUR_UTC).toInstant();

    List<String> contentIds = allFavourites.stream()
        .filter(f -> f.createdAt() != null)
        .filter(f -> !f.createdAt().isBefore(from) && !f.createdAt().isAfter(to))
        .map(Favourite::contentId)
        .toList();

    List<Blog> existing = digestsInWeek(blogRepository, publishAt);
    if (alreadyBackfilled(existing, contentIds, articleRepository)) {
      log.info("Week of {} already has a favourites-based digest; skipping",
          weekStart);
      return false;
    }

    try {
      Optional<Blog> saved = digestAgent.generateForWindow(from, to, publishAt);
      if (saved.isEmpty()) {
        log.info("Week of {} produced no digest; leaving existing post(s) "
            + "in place", weekStart);
        return false;
      }
      for (Blog old : existing) {
        blogRepository.delete(old);
        log.info("Replaced old digest '{}' ({}) for week of {}",
            old.title(), old.id(), weekStart);
      }
      return true;
    } catch (Exception e) {
      // One bad week must not abort the migration or application boot; the
      // remaining weeks still run and this one keeps whatever it already had.
      log.error("Backfill failed for week of {}; leaving it unchanged",
          weekStart, e);
      return false;
    }
  }

  /**
   * The digest that covers a given week: the one stamped at the following
   * Monday's publish time, within a day either side to absorb generation
   * latency and manual re-runs.
   *
   * <p>Deliberately NOT "digests created inside the favourites window". A post
   * published on Monday the 27th covers the week *before* it, so treating it as
   * belonging to the week starting the 27th makes one backfilled week delete
   * two posts — the one it replaces and the previous week's. That is exactly
   * what happened the first time this ran against real data.
   */
  private static List<Blog> digestsInWeek(
      final BlogRepository blogRepository,
      final Instant publishAt) {
    Instant windowStart = publishAt.minusSeconds(86_400);
    Instant windowEnd = publishAt.plusSeconds(86_400);
    List<Blog> matches = new ArrayList<>();
    for (Blog blog : blogRepository.findByPublishedTrueOrderByCreatedDateDesc()) {
      if (blog.contentType() != BlogContentType.DIGEST
          || blog.createdDate() == null) {
        continue;
      }
      Instant created = blog.createdDate();
      if (!created.isBefore(windowStart) && !created.isAfter(windowEnd)) {
        matches.add(blog);
      }
    }
    matches.sort(Comparator.comparing(Blog::createdDate));
    return matches;
  }

  /**
   * True when one of the week's existing digests already links every article
   * favourited that week — the state this change unit produces, so a replay is
   * a no-op rather than a delete-and-regenerate loop.
   *
   * <p>The digest links an article by its {@code originalUrl}, not by the id
   * the favourite holds, so ids are resolved through the article repository
   * first. Favourites whose article no longer resolves are ignored here for the
   * same reason the agent skips them.
   */
  private static boolean alreadyBackfilled(
      final List<Blog> existing,
      final List<String> contentIds,
      final AggregatedArticleRepository articleRepository) {
    if (existing.isEmpty() || contentIds.isEmpty()) {
      return false;
    }
    List<String> urls = contentIds.stream()
        .map(articleRepository::findById)
        .flatMap(Optional::stream)
        .map(AggregatedArticle::originalUrl)
        .filter(url -> url != null && !url.isBlank())
        .toList();
    if (urls.isEmpty()) {
      return false;
    }
    return existing.stream().anyMatch(blog -> {
      String content = blog.content();
      return content != null && !content.isBlank()
          && urls.stream().allMatch(content::contains);
    });
  }

  private static LocalDate weekStartOf(final Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate()
        .with(DayOfWeek.MONDAY);
  }

  @RollbackExecution
  public void rollback() {
    // Backfilled posts are ordinary blog documents and the old-style digests
    // they replaced are gone; there is no mechanical inverse. Restore from a
    // backup if a rollback is ever genuinely needed.
  }
}
