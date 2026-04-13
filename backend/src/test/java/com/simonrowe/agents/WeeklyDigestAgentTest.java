package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Tag;
import com.simonrowe.blog.TagRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

@ExtendWith(MockitoExtension.class)
class WeeklyDigestAgentTest {

  @Mock private BlogRepository blogRepository;
  @Mock private TagRepository tagRepository;
  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private ChatClient.Builder chatClientBuilder;
  @Mock private ContentChangePublisher changePublisher;

  private ChatClient chatClient;
  private ChatClientRequestSpec promptSpec;
  private CallResponseSpec callResponse;

  private WeeklyDigestAgent agent;

  private static final Tag DIGEST_TAG = new Tag("tag-1", "Weekly Digest");

  @BeforeEach
  void setUp() {
    chatClient = mock(ChatClient.class);
    promptSpec = mock(ChatClientRequestSpec.class);
    callResponse = mock(CallResponseSpec.class);

    lenient().when(chatClientBuilder.build()).thenReturn(chatClient);
    lenient().when(chatClient.prompt()).thenReturn(promptSpec);
    lenient().when(promptSpec.user(anyString())).thenReturn(promptSpec);
    lenient().when(promptSpec.call()).thenReturn(callResponse);

    agent = new WeeklyDigestAgent(
        blogRepository, tagRepository, articleRepository, chatClientBuilder, changePublisher);
  }

  @Test
  void generateDigest_skipsWhenNoRecentContent() {
    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc()).thenReturn(List.of());
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc()).thenReturn(List.of());

    agent.generateDigest();

    verify(blogRepository, never()).save(any());
    verify(changePublisher, never()).publishCreated(any(), any());
  }

  @Test
  void generateDigest_skipsWhenOnlyOldContent() {
    Instant twoWeeksAgo = Instant.now().minus(14, ChronoUnit.DAYS);

    Blog oldBlog = new Blog(
        "blog-old", "Old Post", "An old post", "Content", true, null,
        twoWeeksAgo, twoWeeksAgo, List.of(), List.of());
    AggregatedArticle oldArticle = new AggregatedArticle(
        "art-old", "Old Article", "Source", "https://src.com",
        "https://src.com/old", "Summary", "Full content",
        "Author", twoWeeksAgo, twoWeeksAgo, true, null);

    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(oldBlog));
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(oldArticle));

    agent.generateDigest();

    verify(blogRepository, never()).save(any());
    verify(changePublisher, never()).publishCreated(any(), any());
  }

  @Test
  void generateDigest_createsDigestBlogPostFromRecentArticles() {
    Instant recentFetch = Instant.now().minus(2, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-1", "Spring Boot 4 Released", "InfoQ", "https://infoq.com",
        "https://infoq.com/spring-boot-4", "Major new release with virtual threads support.",
        "Full content body", "Jane Doe", recentFetch, recentFetch, true, null);

    Blog savedDigest = new Blog(
        "blog-digest-1", "Week in Review: Apr 6 - Apr 13, 2026",
        "Weekly summary of site activity and tech news",
        "Generated content here.", true, null,
        Instant.now(), Instant.now(), List.of(DIGEST_TAG), List.of());

    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc()).thenReturn(List.of());
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest")).thenReturn(Optional.of(DIGEST_TAG));
    when(callResponse.content()).thenReturn("# Week in Review\n\nSome generated content.");
    when(blogRepository.save(any())).thenReturn(savedDigest);

    agent.generateDigest();

    ArgumentCaptor<Blog> captor = ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(captor.capture());
    Blog created = captor.getValue();

    assertThat(created.id()).isNull();
    assertThat(created.title()).startsWith("Week in Review:");
    assertThat(created.shortDescription())
        .isEqualTo("Weekly summary of site activity and tech news");
    assertThat(created.published()).isTrue();
    assertThat(created.content()).isEqualTo("# Week in Review\n\nSome generated content.");
    assertThat(created.tags()).containsExactly(DIGEST_TAG);
    assertThat(created.createdDate()).isNotNull();
    assertThat(created.updatedDate()).isNotNull();
  }

  @Test
  void generateDigest_createsDigestBlogPostFromRecentBlogPosts() {
    Instant recentCreated = Instant.now().minus(3, ChronoUnit.DAYS);
    Blog recentBlog = new Blog(
        "blog-1", "My New Post", "A short description", "Post content", true, null,
        recentCreated, recentCreated, List.of(), List.of());

    Blog savedDigest = new Blog(
        "blog-digest-2", "Week in Review: Apr 6 - Apr 13, 2026",
        "Weekly summary of site activity and tech news",
        "Generated content.", true, null,
        Instant.now(), Instant.now(), List.of(DIGEST_TAG), List.of());

    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of(recentBlog));
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc()).thenReturn(List.of());
    when(tagRepository.findByName("Weekly Digest")).thenReturn(Optional.of(DIGEST_TAG));
    when(callResponse.content()).thenReturn("Generated content.");
    when(blogRepository.save(any())).thenReturn(savedDigest);

    agent.generateDigest();

    verify(blogRepository).save(any(Blog.class));
    verify(changePublisher).publishCreated(ContentType.BLOG, "blog-digest-2");
  }

  @Test
  void generateDigest_usesLlmForContent() {
    Instant recentFetch = Instant.now().minus(1, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-2", "Embabel 1.0 Ships", "The Register", "https://theregister.com",
        "https://theregister.com/embabel", "Embabel reaches 1.0 milestone.",
        "Full article body text", null, recentFetch, recentFetch, true, null);

    Blog savedDigest = new Blog(
        "blog-digest-3", "Week in Review",
        "Weekly summary of site activity and tech news",
        "LLM generated digest content.", true, null,
        Instant.now(), Instant.now(), List.of(DIGEST_TAG), List.of());

    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc()).thenReturn(List.of());
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest")).thenReturn(Optional.of(DIGEST_TAG));
    when(callResponse.content()).thenReturn("LLM generated digest content.");
    when(blogRepository.save(any())).thenReturn(savedDigest);

    agent.generateDigest();

    // The LLM prompt should have been invoked with user content
    verify(chatClient).prompt();
    verify(promptSpec).user(anyString());
    verify(promptSpec).call();
    verify(callResponse).content();

    ArgumentCaptor<Blog> captor = ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(captor.capture());
    assertThat(captor.getValue().content()).isEqualTo("LLM generated digest content.");
  }

  @Test
  void generateDigest_usesLlmPromptContainingArticleTitleAndSource() {
    Instant recentFetch = Instant.now().minus(1, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-3", "Kafka 4.0 Released", "Hacker News", "https://news.ycombinator.com",
        "https://news.ycombinator.com/kafka-4", "Major release with improvements.",
        "Content body", null, recentFetch, recentFetch, true, null);

    Blog savedDigest = new Blog(
        "blog-digest-4", "Week in Review",
        "Weekly summary of site activity and tech news",
        "Content.", true, null, Instant.now(), Instant.now(), List.of(DIGEST_TAG), List.of());

    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc()).thenReturn(List.of());
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest")).thenReturn(Optional.of(DIGEST_TAG));
    when(callResponse.content()).thenReturn("Content.");
    when(blogRepository.save(any())).thenReturn(savedDigest);

    agent.generateDigest();

    ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
    verify(promptSpec).user(promptCaptor.capture());
    String prompt = promptCaptor.getValue();

    assertThat(prompt).contains("Kafka 4.0 Released");
    assertThat(prompt).contains("Hacker News");
    assertThat(prompt).contains("Major release with improvements.");
  }

  @Test
  void generateDigest_createsDigestTagWhenNotFound() {
    Instant recentFetch = Instant.now().minus(1, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-4", "Testcontainers Cloud GA", "DZone", "https://dzone.com",
        "https://dzone.com/tc-cloud", "Testcontainers Cloud is now generally available.",
        "Body text", null, recentFetch, recentFetch, true, null);

    Tag newTag = new Tag("tag-new", "Weekly Digest");
    Blog savedDigest = new Blog(
        "blog-digest-5", "Week in Review",
        "Weekly summary of site activity and tech news",
        "Content.", true, null, Instant.now(), Instant.now(), List.of(newTag), List.of());

    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc()).thenReturn(List.of());
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest")).thenReturn(Optional.empty());
    when(tagRepository.save(any(Tag.class))).thenReturn(newTag);
    when(callResponse.content()).thenReturn("Content.");
    when(blogRepository.save(any())).thenReturn(savedDigest);

    agent.generateDigest();

    verify(tagRepository).save(any(Tag.class));
    ArgumentCaptor<Blog> captor = ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(captor.capture());
    assertThat(captor.getValue().tags()).containsExactly(newTag);
  }

  @Test
  void generateDigest_publishesCreatedEventAfterSave() {
    Instant recentFetch = Instant.now().minus(2, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-5", "OpenTelemetry Reaches 1.0 Stability", "CNCF Blog", "https://cncf.io",
        "https://cncf.io/otel-1", "OTel SDK hits 1.0 stability.",
        "Full content text", null, recentFetch, recentFetch, true, null);

    Blog savedDigest = new Blog(
        "saved-blog-id", "Week in Review",
        "Weekly summary of site activity and tech news",
        "Content.", true, null, Instant.now(), Instant.now(), List.of(DIGEST_TAG), List.of());

    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc()).thenReturn(List.of());
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest")).thenReturn(Optional.of(DIGEST_TAG));
    when(callResponse.content()).thenReturn("Content.");
    when(blogRepository.save(any())).thenReturn(savedDigest);

    agent.generateDigest();

    verify(changePublisher).publishCreated(ContentType.BLOG, "saved-blog-id");
  }

  @Test
  void generateDigest_fallsBackToRawSummaryWhenLlmFails() {
    Instant recentFetch = Instant.now().minus(1, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-6", "GraalVM 24 Released", "Oracle Blog", "https://blogs.oracle.com",
        "https://blogs.oracle.com/graalvm-24", "GraalVM 24 delivers faster startup.",
        "Content body", null, recentFetch, recentFetch, true, null);

    Blog savedDigest = new Blog(
        "blog-digest-6", "Week in Review",
        "Weekly summary of site activity and tech news",
        "Fallback content.", true, null, Instant.now(), Instant.now(),
        List.of(DIGEST_TAG), List.of());

    when(blogRepository.findByPublishedTrueOrderByCreatedDateDesc()).thenReturn(List.of());
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest")).thenReturn(Optional.of(DIGEST_TAG));
    when(callResponse.content()).thenThrow(new RuntimeException("LLM timeout"));
    when(blogRepository.save(any())).thenReturn(savedDigest);

    // Should not throw; falls back to raw summary
    agent.generateDigest();

    ArgumentCaptor<Blog> captor = ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(captor.capture());
    // When LLM fails the raw activity summary is used as content — it will contain
    // the article title and source name
    assertThat(captor.getValue().content()).contains("GraalVM 24 Released");
    assertThat(captor.getValue().content()).contains("Oracle Blog");
  }
}
