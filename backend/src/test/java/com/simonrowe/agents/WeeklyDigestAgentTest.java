package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeeklyDigestAgentTest {

  private static final Tag DIGEST_TAG = new Tag("tag-1", "Weekly Digest");
  private static final int WINDOW_DAYS = 7;

  @Mock private BlogRepository blogRepository;
  @Mock private TagRepository tagRepository;
  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private FavouriteRepository favouriteRepository;
  @Mock private ArticleSectionWriter sectionWriter;
  @Mock private DigestComposer composer;
  @Mock private DigestMetadataGenerator metadataGenerator;
  @Mock private ContentChangePublisher changePublisher;
  @Mock private BlogImageGenerationService blogImageGenerationService;

  private WeeklyDigestAgent agent;

  private static AggregatedArticle article(final String id, final String title) {
    return new AggregatedArticle(
        id, title, "InfoQ", "https://infoq.com",
        "https://infoq.com/" + id, "Stored summary.", "Full content.",
        "Jane Doe", Instant.now(), Instant.now(), true, null);
  }

  private static Favourite favourite(final String contentId, final int daysAgo) {
    return new Favourite(
        "fav-" + contentId, FavouriteType.NEWS, contentId,
        Instant.now().minus(daysAgo, ChronoUnit.DAYS));
  }

  private static Blog digestBlog(final String id, final int daysAgo) {
    Instant createdAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
    return new Blog(
        id, "Existing digest", "desc", "content", true, "/uploads/x.png",
        createdAt, createdAt, List.of(DIGEST_TAG), List.<Skill>of(),
        BlogContentType.DIGEST);
  }

  @BeforeEach
  void setUp() {
    lenient().when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.of(DIGEST_TAG));
    lenient().when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of());
    agent = new WeeklyDigestAgent(
        blogRepository, tagRepository, articleRepository, favouriteRepository,
        sectionWriter, composer, metadataGenerator, changePublisher,
        blogImageGenerationService, WINDOW_DAYS);
  }

  @Test
  void suppressesTheRunWhenDigestAlreadyExistsInsideTheWindow() {
    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(digestBlog("existing-1", 2)));

    agent.generateDigest();

    verify(favouriteRepository, never())
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(any(), any());
    verify(sectionWriter, never()).write(any());
    verify(composer, never()).compose(anyList());
    verify(blogRepository, never()).save(any());
    verify(changePublisher, never()).publishCreated(any(), any());
  }

  @Test
  void doesNotSuppressTheRunWhenTheExistingDigestIsOutsideTheWindow() {
    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(digestBlog("stale-digest", WINDOW_DAYS + 3)));
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of());

    agent.generateDigest();

    verify(favouriteRepository)
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any());
  }

  @Test
  void publishesNothingWhenNoFavouritesInWindow() {
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of());

    agent.generateDigest();

    verify(blogRepository, never()).save(any());
    verify(changePublisher, never()).publishCreated(any(), any());
  }

  @Test
  void skipsFavouriteWhoseArticleNoLongerExists() {
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of(favourite("gone", 1)));
    when(articleRepository.findById("gone")).thenReturn(Optional.empty());

    agent.generateDigest();

    verify(sectionWriter, never()).write(any());
    verify(blogRepository, never()).save(any());
  }

  @Test
  void skipsFavouriteWhoseArticleIsHidden() {
    AggregatedArticle hidden = new AggregatedArticle(
        "hid", "Hidden", "InfoQ", "https://infoq.com",
        "https://infoq.com/hid", "Summary.", "Content.", null,
        Instant.now(), Instant.now(), false, null);
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of(favourite("hid", 1)));
    when(articleRepository.findById("hid")).thenReturn(Optional.of(hidden));

    agent.generateDigest();

    verify(sectionWriter, never()).write(any());
    verify(blogRepository, never()).save(any());
  }

  @Test
  void queriesUsingTheConfiguredWindow() {
    // Uses a window distinct from both WINDOW_DAYS (7, shared by the other
    // tests) and the production default (also 7), so an agent that ignored
    // the injected value and hardcoded 7 days would fail this assertion.
    int distinctWindowDays = 3;
    WeeklyDigestAgent agentWithDistinctWindow = new WeeklyDigestAgent(
        blogRepository, tagRepository, articleRepository, favouriteRepository,
        sectionWriter, composer, metadataGenerator, changePublisher,
        blogImageGenerationService, distinctWindowDays);
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of());

    agentWithDistinctWindow.generateDigest();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(favouriteRepository)
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), cutoff.capture());
    Instant expected =
        Instant.now().minus(distinctWindowDays, ChronoUnit.DAYS);
    assertThat(cutoff.getValue())
        .isBetween(expected.minusSeconds(60), expected.plusSeconds(60));
  }

  @Test
  void publishesNothingWhenEverySectionIsFallback() {
    AggregatedArticle art = article("art-1", "Spring Boot 4");
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of(favourite("art-1", 1)));
    when(articleRepository.findById("art-1")).thenReturn(Optional.of(art));
    when(sectionWriter.write(art)).thenReturn(new DigestSection(
        "art-1", "Spring Boot 4", "https://infoq.com/art-1",
        "Stored summary.", true));

    agent.generateDigest();

    verify(composer, never()).compose(anyList());
    verify(blogRepository, never()).save(any());
  }

  @Test
  void publishesWhenOnlySomeSectionsAreFallback() {
    AggregatedArticle fallbackArticle = article("art-1", "Spring Boot 4");
    AggregatedArticle realArticle = article("art-2", "GraalVM 24");
    DigestSection fallbackSection = new DigestSection(
        "art-1", "Spring Boot 4", "https://infoq.com/art-1",
        "Stored summary.", true);
    DigestSection realSection = new DigestSection(
        "art-2", "GraalVM 24", "https://infoq.com/art-2",
        "Real prose.", false);

    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of(
            favourite("art-1", 1), favourite("art-2", 2)));
    when(articleRepository.findById("art-1"))
        .thenReturn(Optional.of(fallbackArticle));
    when(articleRepository.findById("art-2"))
        .thenReturn(Optional.of(realArticle));
    when(sectionWriter.write(fallbackArticle)).thenReturn(fallbackSection);
    when(sectionWriter.write(realArticle)).thenReturn(realSection);
    when(composer.compose(List.of(fallbackSection, realSection)))
        .thenReturn("## [Spring Boot 4](https://infoq.com/art-1)\n\n"
            + "Stored summary.\n\n"
            + "## [GraalVM 24](https://infoq.com/art-2)\n\nReal prose.");
    when(metadataGenerator.generate(anyList(), anyString()))
        .thenReturn(new DigestMetadata("What caught my eye", "A description"));
    when(blogImageGenerationService
        .generateAndStore(anyString(), anyString(), anyString()))
        .thenReturn("/uploads/digest.png");
    when(blogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    agent.generateDigest();

    verify(composer).compose(List.of(fallbackSection, realSection));
    verify(blogRepository).save(any(Blog.class));
  }

  @Test
  void publishesDigestFromFavouritedArticles() {
    AggregatedArticle art = article("art-1", "Spring Boot 4");
    DigestSection section = new DigestSection(
        "art-1", "Spring Boot 4", "https://infoq.com/art-1",
        "Real prose.", false);

    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of(favourite("art-1", 2)));
    when(articleRepository.findById("art-1")).thenReturn(Optional.of(art));
    when(sectionWriter.write(art)).thenReturn(section);
    when(composer.compose(List.of(section)))
        .thenReturn("## [Spring Boot 4](https://infoq.com/art-1)\n\nReal prose.");
    when(metadataGenerator.generate(anyList(), anyString()))
        .thenReturn(new DigestMetadata("What caught my eye", "A short description"));
    when(blogImageGenerationService
        .generateAndStore(anyString(), anyString(), anyString()))
        .thenReturn("/uploads/digest.png");
    when(blogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    agent.generateDigest();

    ArgumentCaptor<Blog> saved = ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(saved.capture());
    Blog digest = saved.getValue();
    assertThat(digest.contentType()).isEqualTo(BlogContentType.DIGEST);
    assertThat(digest.title()).isEqualTo("What caught my eye");
    assertThat(digest.shortDescription()).isEqualTo("A short description");
    assertThat(digest.featuredImageUrl()).isEqualTo("/uploads/digest.png");
    assertThat(digest.content()).contains("https://infoq.com/art-1");
    assertThat(digest.tags()).containsExactly(DIGEST_TAG);
    assertThat(digest.published()).isTrue();
    verify(changePublisher).publishCreated(eq(ContentType.BLOG), any());
  }
}
