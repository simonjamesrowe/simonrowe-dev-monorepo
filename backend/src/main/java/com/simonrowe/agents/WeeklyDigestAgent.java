package com.simonrowe.agents;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Skill;
import com.simonrowe.blog.Tag;
import com.simonrowe.blog.TagRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import com.simonrowe.media.BlogImageGenerationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * Builds the weekly digest from the news articles favourited in the last
 * window, one section per article.
 *
 * <p>The window is fixed rather than measured from the last digest, so a
 * skipped run loses that week's items rather than rolling them forward. That
 * is deliberate: it keeps the job stateless.
 */
@Agent(
    name = "WeeklyDigest",
    description = "Generates a weekly digest blog post summarising the news "
        + "articles favourited over the past week"
)
public class WeeklyDigestAgent {

  private static final Logger log =
      LoggerFactory.getLogger(WeeklyDigestAgent.class);

  private final BlogRepository blogRepository;
  private final TagRepository tagRepository;
  private final AggregatedArticleRepository articleRepository;
  private final FavouriteRepository favouriteRepository;
  private final ArticleSectionWriter sectionWriter;
  private final DigestComposer composer;
  private final DigestMetadataGenerator metadataGenerator;
  private final ContentChangePublisher changePublisher;
  private final BlogImageGenerationService blogImageGenerationService;
  private final int windowDays;

  /**
   * How far back to look for an existing digest before treating this run as a
   * duplicate. Deliberately much shorter than {@link #windowDays}: the digest's
   * {@code createdDate} is stamped after the LLM and image work finishes, so it
   * lands 30-120 seconds after the cron fires. A suppression window equal to the
   * weekly cadence would therefore see last week's digest as "just published"
   * every single week and skip forever. This only has to catch the real case —
   * the cron published this morning and someone hit Trigger Digest afterwards.
   */
  private final int duplicateWindowHours;

  public WeeklyDigestAgent(
      final BlogRepository blogRepository,
      final TagRepository tagRepository,
      final AggregatedArticleRepository articleRepository,
      final FavouriteRepository favouriteRepository,
      final ArticleSectionWriter sectionWriter,
      final DigestComposer composer,
      final DigestMetadataGenerator metadataGenerator,
      final ContentChangePublisher changePublisher,
      final BlogImageGenerationService blogImageGenerationService,
      @Value("${aggregation.digest.window-days}") final int windowDays,
      @Value("${aggregation.digest.duplicate-window-hours}")
      final int duplicateWindowHours) {
    this.blogRepository = blogRepository;
    this.tagRepository = tagRepository;
    this.articleRepository = articleRepository;
    this.favouriteRepository = favouriteRepository;
    this.sectionWriter = sectionWriter;
    this.composer = composer;
    this.metadataGenerator = metadataGenerator;
    this.changePublisher = changePublisher;
    this.blogImageGenerationService = blogImageGenerationService;
    this.windowDays = windowDays;
    this.duplicateWindowHours = duplicateWindowHours;
  }

  /** Generates and publishes the digest, or logs why it did not. */
  @Action(description = "Generate a digest blog post")
  public void generateDigest() {
    Instant cutoff = Instant.now().minus(windowDays, ChronoUnit.DAYS);
    Instant duplicateCutoff =
        Instant.now().minus(duplicateWindowHours, ChronoUnit.HOURS);
    if (digestAlreadyPublishedInWindow(duplicateCutoff)) {
      log.info("A digest already exists within the last {} hours, "
          + "skipping this run", duplicateWindowHours);
      return;
    }

    List<Favourite> favourites = favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            FavouriteType.NEWS, cutoff);
    List<AggregatedArticle> articles = favourites.stream()
        .map(this::resolveArticle)
        .flatMap(Optional::stream)
        .toList();
    if (articles.isEmpty()) {
      if (favourites.isEmpty()) {
        log.info("No news favourited in the last {} days, "
            + "skipping digest generation", windowDays);
      } else {
        log.info("{} favourite(s) in the last {} days all resolved to "
            + "missing or hidden articles, skipping digest generation",
            favourites.size(), windowDays);
      }
      return;
    }

    List<DigestSection> sections = articles.stream()
        .map(sectionWriter::write)
        .toList();

    if (!sections.isEmpty()
        && sections.stream().allMatch(DigestSection::fallback)) {
      log.error("Every section fell back to its stored summary — "
          + "assuming an LLM outage and publishing nothing");
      return;
    }

    String content = composer.compose(sections);
    String activitySummary = buildActivitySummary(sections);
    DigestMetadata metadata =
        metadataGenerator.generate(articles, activitySummary);
    String featuredImageUrl = blogImageGenerationService.generateAndStore(
        metadata.title(), metadata.shortDescription(),
        buildImageContext(articles));

    Tag digestTag = getOrCreateDigestTag();
    Instant createdAt = Instant.now();
    Blog digest = new Blog(
        null, metadata.title(), metadata.shortDescription(),
        content, true, featuredImageUrl, createdAt, createdAt,
        List.of(digestTag), List.<Skill>of(), BlogContentType.DIGEST);

    Blog saved = blogRepository.save(digest);
    changePublisher.publishCreated(ContentType.BLOG, saved.id());
    log.info("Published digest '{}' covering {} favourited articles",
        metadata.title(), articles.size());
  }

  /**
   * True when a digest already exists whose {@code createdDate} falls inside
   * the current window — a repeat trigger (cron plus a manual re-run, or two
   * manual runs) must not publish a second post for the same period.
   */
  private boolean digestAlreadyPublishedInWindow(final Instant cutoff) {
    return blogRepository.findByPublishedTrueOrderByCreatedDateDesc().stream()
        .anyMatch(blog -> blog.contentType() == BlogContentType.DIGEST
            && blog.createdDate() != null
            && blog.createdDate().isAfter(cutoff));
  }

  private Optional<AggregatedArticle> resolveArticle(
      final Favourite favourite) {
    Optional<AggregatedArticle> article =
        articleRepository.findById(favourite.contentId());
    if (article.isEmpty()) {
      log.warn("Favourite {} points at missing article {}, skipping",
          favourite.id(), favourite.contentId());
      return Optional.empty();
    }
    if (!article.get().visible()) {
      log.info("Skipping favourited article '{}' — it is hidden",
          article.get().title());
      return Optional.empty();
    }
    return article;
  }

  private static String buildActivitySummary(
      final List<DigestSection> sections) {
    StringBuilder sb = new StringBuilder("## Favourited This Week\n");
    for (DigestSection section : sections) {
      sb.append("- [").append(section.title())
          .append("](").append(section.url()).append(")\n");
    }
    return sb.toString();
  }

  private static String buildImageContext(
      final List<AggregatedArticle> articles) {
    StringBuilder sb = new StringBuilder("Favourited articles: ");
    articles.stream().limit(8).forEach(article -> sb.append(article.title())
        .append(" from ").append(article.sourceName())
        .append(" - ").append(article.summary()).append("; "));
    return sb.toString();
  }

  private Tag getOrCreateDigestTag() {
    return tagRepository.findByName("Weekly Digest")
        .orElseGet(() -> tagRepository.save(new Tag(null, "Weekly Digest")));
  }
}
