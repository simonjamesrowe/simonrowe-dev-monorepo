package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Tag;
import com.simonrowe.blog.TagRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
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

  @Mock private BlogRepository blogRepository;
  @Mock private TagRepository tagRepository;
  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private Ai ai;
  @Mock private ContentChangePublisher changePublisher;
  @Mock private BlogImageGenerationService blogImageGenerationService;

  private PromptRunner promptRunner;
  private AssistantMessage assistantMessage;

  private WeeklyDigestAgent agent;

  private static final Tag DIGEST_TAG =
      new Tag("tag-1", "Weekly Digest");

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    promptRunner = mock(PromptRunner.class);
    assistantMessage = mock(AssistantMessage.class);

    lenient().when(ai.withLlm("gpt-4o-mini"))
        .thenReturn(promptRunner);
    lenient().when(promptRunner.respond(anyList()))
        .thenReturn(assistantMessage);

    agent = new WeeklyDigestAgent(
        blogRepository, tagRepository,
        articleRepository, ai, changePublisher,
        blogImageGenerationService);
  }

  @Test
  void generateDigest_skipsWhenNoRecentContent() {
    when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.of(DIGEST_TAG));
    when(blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of());
    when(articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of());

    agent.generateDigest();

    verify(blogRepository, never()).save(any());
    verify(changePublisher, never())
        .publishCreated(any(), any());
  }

  @Test
  void generateDigest_skipsWhenOnlyOldContent() {
    when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.of(DIGEST_TAG));
    Instant twoWeeksAgo =
        Instant.now().minus(14, ChronoUnit.DAYS);

    Blog oldBlog = new Blog(
        "blog-old", "Old Post", "An old post",
        "Content", true, null,
        twoWeeksAgo, twoWeeksAgo, List.of(), List.of());
    AggregatedArticle oldArticle = new AggregatedArticle(
        "art-old", "Old Article", "Source",
        "https://src.com", "https://src.com/old",
        "Summary", "Full content", "Author",
        twoWeeksAgo, twoWeeksAgo, true, null);

    when(blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(oldBlog));
    when(articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(oldArticle));

    agent.generateDigest();

    verify(blogRepository, never()).save(any());
    verify(changePublisher, never())
        .publishCreated(any(), any());
  }

  @Test
  void generateDigest_createsDigestFromRecentArticles() {
    Instant recentFetch =
        Instant.now().minus(2, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-1", "Spring Boot 4 Released", "InfoQ",
        "https://infoq.com",
        "https://infoq.com/spring-boot-4",
        "Major new release.", "Full content body",
        "Jane Doe", recentFetch, recentFetch, true, null);

    Blog savedDigest = new Blog(
        "blog-digest-1",
        "Week in Review: Apr 6 - Apr 13, 2026",
        "Latest roundup of site activity and tech news",
        "Generated content.", true, null,
        Instant.now(), Instant.now(),
        List.of(DIGEST_TAG), List.of());

    when(blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of());
    when(articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.of(DIGEST_TAG));
    when(assistantMessage.getContent())
        .thenReturn("# Week in Review\n\nGenerated.");
    when(blogRepository.save(any()))
        .thenReturn(savedDigest);

    agent.generateDigest();

    ArgumentCaptor<Blog> captor =
        ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(captor.capture());
    Blog created = captor.getValue();

    assertThat(created.id()).isNull();
    assertThat(created.title())
        .startsWith("AI & Tech Roundup:");
    assertThat(created.shortDescription())
        .isEqualTo(
            "Latest roundup of site activity and tech news");
    assertThat(created.published()).isTrue();
    assertThat(created.content())
        .isEqualTo("# Week in Review\n\nGenerated.");
    assertThat(created.tags())
        .containsExactly(DIGEST_TAG);
  }

  @Test
  void generateDigest_createsDigestFromRecentBlogPosts() {
    Instant recentCreated =
        Instant.now().minus(3, ChronoUnit.DAYS);
    Blog recentBlog = new Blog(
        "blog-1", "My New Post", "A short description",
        "Post content", true, null,
        recentCreated, recentCreated, List.of(), List.of());

    Blog savedDigest = new Blog(
        "blog-digest-2",
        "Week in Review: Apr 6 - Apr 13, 2026",
        "Latest roundup of site activity and tech news",
        "Generated content.", true, null,
        Instant.now(), Instant.now(),
        List.of(DIGEST_TAG), List.of());

    when(blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(recentBlog));
    when(articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of());
    when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.of(DIGEST_TAG));
    when(assistantMessage.getContent())
        .thenReturn("Generated content.");
    when(blogRepository.save(any()))
        .thenReturn(savedDigest);

    agent.generateDigest();

    verify(blogRepository).save(any(Blog.class));
    verify(changePublisher).publishCreated(
        ContentType.BLOG, "blog-digest-2");
  }

  @Test
  void generateDigest_usesEmbabelAiForContent() {
    Instant recentFetch =
        Instant.now().minus(1, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-2", "Embabel 1.0 Ships", "The Register",
        "https://theregister.com",
        "https://theregister.com/embabel",
        "Embabel reaches 1.0 milestone.",
        "Full article body text", null,
        recentFetch, recentFetch, true, null);

    Blog savedDigest = new Blog(
        "blog-digest-3", "Week in Review",
        "Latest roundup of site activity and tech news",
        "LLM generated content.", true, null,
        Instant.now(), Instant.now(),
        List.of(DIGEST_TAG), List.of());

    when(blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of());
    when(articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.of(DIGEST_TAG));
    when(assistantMessage.getContent())
        .thenReturn("LLM generated content.");
    when(blogRepository.save(any()))
        .thenReturn(savedDigest);

    agent.generateDigest();

    verify(ai).withLlm("gpt-4o-mini");
    verify(promptRunner).respond(anyList());

    ArgumentCaptor<Blog> captor =
        ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(captor.capture());
    assertThat(captor.getValue().content())
        .isEqualTo("LLM generated content.");
  }

  @Test
  void generateDigest_createsDigestTagWhenNotFound() {
    Instant recentFetch =
        Instant.now().minus(1, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-4", "Testcontainers Cloud GA", "DZone",
        "https://dzone.com",
        "https://dzone.com/tc-cloud",
        "Testcontainers Cloud is now GA.",
        "Body text", null,
        recentFetch, recentFetch, true, null);

    Tag newTag = new Tag("tag-new", "Weekly Digest");
    Blog savedDigest = new Blog(
        "blog-digest-5", "Week in Review",
        "Latest roundup of site activity and tech news",
        "Content.", true, null,
        Instant.now(), Instant.now(),
        List.of(newTag), List.of());

    when(blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of());
    when(articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.empty());
    when(tagRepository.save(any(Tag.class)))
        .thenReturn(newTag);
    when(assistantMessage.getContent())
        .thenReturn("Content.");
    when(blogRepository.save(any()))
        .thenReturn(savedDigest);

    agent.generateDigest();

    verify(tagRepository).save(any(Tag.class));
    ArgumentCaptor<Blog> captor =
        ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(captor.capture());
    assertThat(captor.getValue().tags())
        .containsExactly(newTag);
  }

  @Test
  void generateDigest_publishesCreatedEventAfterSave() {
    Instant recentFetch =
        Instant.now().minus(2, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-5", "OTel Reaches 1.0", "CNCF Blog",
        "https://cncf.io", "https://cncf.io/otel-1",
        "OTel SDK hits 1.0 stability.",
        "Full content text", null,
        recentFetch, recentFetch, true, null);

    Blog savedDigest = new Blog(
        "saved-blog-id", "Week in Review",
        "Latest roundup of site activity and tech news",
        "Content.", true, null,
        Instant.now(), Instant.now(),
        List.of(DIGEST_TAG), List.of());

    when(blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of());
    when(articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.of(DIGEST_TAG));
    when(assistantMessage.getContent())
        .thenReturn("Content.");
    when(blogRepository.save(any()))
        .thenReturn(savedDigest);

    agent.generateDigest();

    verify(changePublisher).publishCreated(
        ContentType.BLOG, "saved-blog-id");
  }

  @Test
  void generateDigest_fallsBackToRawSummaryOnLlmFailure() {
    Instant recentFetch =
        Instant.now().minus(1, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-6", "GraalVM 24 Released", "Oracle Blog",
        "https://blogs.oracle.com",
        "https://blogs.oracle.com/graalvm-24",
        "GraalVM 24 delivers faster startup.",
        "Content body", null,
        recentFetch, recentFetch, true, null);

    Blog savedDigest = new Blog(
        "blog-digest-6", "Week in Review",
        "Latest roundup of site activity and tech news",
        "Fallback content.", true, null,
        Instant.now(), Instant.now(),
        List.of(DIGEST_TAG), List.of());

    when(blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of());
    when(articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.of(DIGEST_TAG));
    when(promptRunner.respond(anyList()))
        .thenThrow(new RuntimeException("LLM timeout"));
    when(blogRepository.save(any()))
        .thenReturn(savedDigest);

    agent.generateDigest();

    ArgumentCaptor<Blog> captor =
        ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(captor.capture());
    assertThat(captor.getValue().content())
        .contains("GraalVM 24 Released");
    assertThat(captor.getValue().content())
        .contains("Oracle Blog");
  }
}
