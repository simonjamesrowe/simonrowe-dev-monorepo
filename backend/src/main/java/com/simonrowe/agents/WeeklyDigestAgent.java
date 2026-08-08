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
      @Value("${aggregation.digest.window-days}") final int windowDays) {
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
  }

  /** Generates and publishes the digest, or logs why it did not. */
  @Action(description = "Generate a digest blog post")
  public void generateDigest() {
    List<AggregatedArticle> articles = favouritedArticles();
    if (articles.isEmpty()) {
      log.info("No news favourited in the last {} days, "
          + "skipping digest generation", windowDays);
      return;
    }

    List<DigestSection> sections = articles.stream()
        .map(sectionWriter::write)
        .toList();

    if (sections.stream().allMatch(DigestSection::fallback)) {
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

  private List<AggregatedArticle> favouritedArticles() {
    Instant cutoff = Instant.now().minus(windowDays, ChronoUnit.DAYS);
    List<Favourite> favourites = favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            FavouriteType.NEWS, cutoff);
    return favourites.stream()
        .map(this::resolveArticle)
        .flatMap(Optional::stream)
        .toList();
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
