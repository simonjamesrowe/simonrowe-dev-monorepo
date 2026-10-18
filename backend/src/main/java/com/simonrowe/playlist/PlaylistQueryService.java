package com.simonrowe.playlist;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import com.simonrowe.media.MediaVariantResolver;
import com.simonrowe.narration.NarrationContentType;
import com.simonrowe.narration.NarrationResponse;
import com.simonrowe.narration.NarrationService;
import com.simonrowe.narration.ReadyNarration;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryResponse;
import com.simonrowe.summary.ArticleSummaryService;
import com.simonrowe.summary.SummaryStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Assembles the playlist page's one read: every item, joined with its content, summary,
 * favourite and audio state.
 *
 * <p>Each kind of join is one query for the whole list rather than one per item. The list can
 * hold hundreds of items, because everything summarised or hearted joins it.
 */
@Service
public class PlaylistQueryService {

  /**
   * Speaking rate behind the length estimate for items with no audio yet. The Chirp HD voice
   * reads about 150 words a minute, roughly 14 characters a second. It is only ever shown as
   * an approximate number of minutes.
   */
  static final double CHARACTERS_PER_SECOND = 14.0;

  static final String SITE_AUTHOR = "Simon Rowe";

  private static final Comparator<Instant> NEWEST_FIRST =
      Comparator.nullsLast(Comparator.reverseOrder());

  /**
   * Play order: "play next" first, the latest choice first; then the content's own date,
   * newest first, so today's news plays before last week's; then the newest addition.
   */
  static final Comparator<PlaylistEntry> PLAY_ORDER =
      Comparator.comparing(PlaylistEntry::pinnedAt, NEWEST_FIRST)
          .thenComparing(PlaylistEntry::publishedAt, NEWEST_FIRST)
          .thenComparing(PlaylistEntry::addedAt, NEWEST_FIRST);

  private static final Comparator<PlaylistEntry> MOST_RECENTLY_HEARD =
      Comparator.comparing(PlaylistEntry::listenedAt, NEWEST_FIRST);

  private final PlaylistService playlistService;
  private final MongoTemplate mongoTemplate;
  private final AggregatedArticleRepository articleRepository;
  private final BlogRepository blogRepository;
  private final ArticleSummaryService summaryService;
  private final FavouriteRepository favouriteRepository;
  private final NarrationService narrationService;
  private final MediaVariantResolver mediaVariantResolver;

  public PlaylistQueryService(
      final PlaylistService playlistService,
      final MongoTemplate mongoTemplate,
      final AggregatedArticleRepository articleRepository,
      final BlogRepository blogRepository,
      final ArticleSummaryService summaryService,
      final FavouriteRepository favouriteRepository,
      final NarrationService narrationService,
      final MediaVariantResolver mediaVariantResolver
  ) {
    this.playlistService = playlistService;
    this.mongoTemplate = mongoTemplate;
    this.articleRepository = articleRepository;
    this.blogRepository = blogRepository;
    this.summaryService = summaryService;
    this.favouriteRepository = favouriteRepository;
    this.narrationService = narrationService;
    this.mediaVariantResolver = mediaVariantResolver;
  }

  /**
   * The whole playlist: queued items in play order (see {@link #PLAY_ORDER}), then listened
   * items, most recent first.
   * Items whose content has since been deleted, hidden or unpublished are left out.
   *
   * @return the playlist
   */
  public PlaylistResponse playlist() {
    List<PlaylistItem> items = mongoTemplate.findAll(PlaylistItem.class);
    Set<String> articleIds = idsOf(items, PlaylistContentType.ARTICLE_SUMMARY);
    Set<String> blogIds = idsOf(items, PlaylistContentType.BLOG);

    Joins joins = new Joins(
        byId(articleRepository.findAllById(articleIds), AggregatedArticle::id),
        byId(blogRepository.findAllById(blogIds), Blog::id),
        summaryService.findForAll(articleIds),
        favouriteRepository.findByType(FavouriteType.NEWS).stream()
            .map(Favourite::contentId)
            .collect(Collectors.toSet()),
        readyAudio(NarrationContentType.ARTICLE_SUMMARY),
        readyAudio(NarrationContentType.BLOG));
    Map<String, NarrationResponse.PublicState> articleStates = narrationService.latestStates(
        NarrationContentType.ARTICLE_SUMMARY, without(articleIds, joins.readyArticles()));
    Map<String, NarrationResponse.PublicState> blogStates = narrationService.latestStates(
        NarrationContentType.BLOG, without(blogIds, joins.readyBlogs()));

    List<PlaylistEntry> entries = new ArrayList<>(items.size());
    for (PlaylistItem item : items) {
      PlaylistEntry entry = switch (item.contentType()) {
        case ARTICLE_SUMMARY -> articleEntry(item, joins, articleStates);
        case BLOG -> blogEntry(item, joins, blogStates);
      };
      if (entry != null) {
        entries.add(entry);
      }
    }
    List<PlaylistEntry> ordered = new ArrayList<>(entries.size());
    entries.stream().filter(entry -> entry.status() == PlaylistItemStatus.QUEUED)
        .sorted(PLAY_ORDER).forEach(ordered::add);
    entries.stream().filter(entry -> entry.status() == PlaylistItemStatus.LISTENED)
        .sorted(MOST_RECENTLY_HEARD).forEach(ordered::add);
    return new PlaylistResponse(ordered, playlistService.settings().autoVoice());
  }

  private PlaylistEntry articleEntry(
      final PlaylistItem item,
      final Joins joins,
      final Map<String, NarrationResponse.PublicState> states
  ) {
    AggregatedArticle article = joins.articles().get(item.contentId());
    if (article == null || !article.visible()) {
      return null;
    }
    ArticleSummary summary = joins.summaries().get(item.contentId());
    ReadyNarration ready = joins.readyArticles().get(item.contentId());
    Long estimate = null;
    if (ready == null && summary != null && summary.status() == SummaryStatus.READY) {
      estimate = estimate(length(article.title()) + length(summary.body()));
    }
    return new PlaylistEntry(
        item.contentType(), item.contentId(), item.status(), item.progressSeconds(),
        item.addedVia(), item.addedAt(), item.listenedAt(), item.voiceRequested(),
        item.pinnedAt(), article.title(), article.originalUrl(), true, article.sourceName(),
        article.imageUrl(), article.publishedDate(),
        joins.favouriteArticleIds().contains(item.contentId()),
        summary == null ? ArticleSummaryResponse.PublicState.NOT_REQUESTED
            : ArticleSummaryResponse.from(summary).state(),
        summary == null ? null : summary.retryable(),
        audio(ready, states.get(item.contentId())),
        estimate);
  }

  private PlaylistEntry blogEntry(
      final PlaylistItem item,
      final Joins joins,
      final Map<String, NarrationResponse.PublicState> states
  ) {
    Blog blog = joins.blogs().get(item.contentId());
    if (blog == null || !blog.published()) {
      return null;
    }
    ReadyNarration ready = joins.readyBlogs().get(item.contentId());
    return new PlaylistEntry(
        item.contentType(), item.contentId(), item.status(), item.progressSeconds(),
        item.addedVia(), item.addedAt(), item.listenedAt(), item.voiceRequested(),
        item.pinnedAt(), blog.title(), "/blogs/" + blog.id(), false, SITE_AUTHOR,
        mediaVariantResolver.resolvePath(blog.featuredImageUrl(), "small", "medium", "large"),
        blog.createdDate(),
        null, null, null,
        audio(ready, states.get(item.contentId())),
        ready == null ? estimate(length(blog.title()) + length(blog.content())) : null);
  }

  private static PlaylistEntry.Audio audio(
      final ReadyNarration ready,
      final NarrationResponse.PublicState latest
  ) {
    if (ready != null) {
      return new PlaylistEntry.Audio(NarrationResponse.PublicState.READY,
          ready.audioUrl(), ready.durationSeconds(), ready.timingsUrl());
    }
    // A READY row missing from readyNarrations has no playable file; treat it as absent.
    NarrationResponse.PublicState state = latest == null
        || latest == NarrationResponse.PublicState.READY
        ? NarrationResponse.PublicState.NOT_REQUESTED : latest;
    return new PlaylistEntry.Audio(state, null, null, null);
  }

  private Map<String, ReadyNarration> readyAudio(final NarrationContentType contentType) {
    return byId(narrationService.readyNarrations(contentType), ReadyNarration::contentId);
  }

  static long estimate(final int characters) {
    return Math.max(1L, Math.round(characters / CHARACTERS_PER_SECOND));
  }

  private static int length(final String text) {
    return text == null ? 0 : text.length();
  }

  private static Set<String> idsOf(
      final List<PlaylistItem> items,
      final PlaylistContentType contentType
  ) {
    return items.stream()
        .filter(item -> item.contentType() == contentType)
        .map(PlaylistItem::contentId)
        .collect(Collectors.toSet());
  }

  private static Set<String> without(final Set<String> ids, final Map<String, ?> exclude) {
    return ids.stream().filter(id -> !exclude.containsKey(id)).collect(Collectors.toSet());
  }

  private static <T> Map<String, T> byId(
      final Iterable<T> values,
      final Function<T, String> idOf
  ) {
    Map<String, T> map = new HashMap<>();
    values.forEach(value -> map.putIfAbsent(idOf.apply(value), value));
    return map;
  }

  private record Joins(
      Map<String, AggregatedArticle> articles,
      Map<String, Blog> blogs,
      Map<String, ArticleSummary> summaries,
      Set<String> favouriteArticleIds,
      Map<String, ReadyNarration> readyArticles,
      Map<String, ReadyNarration> readyBlogs
  ) {
  }
}
