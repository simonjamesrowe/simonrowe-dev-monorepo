package com.simonrowe.embedding;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.events.ContentChangeEvent;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangeEvent.EventType;
import com.simonrowe.skills.SkillGroupRepository;
import com.simonrowe.skills.SkillRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code sourceId} each event removes or writes vectors under.
 *
 * <p>Aggregated content is embedded under a prefixed id, so a delete has to use the same
 * prefix or it removes nothing and the deleted article stays answerable in chat.
 */
class EmbeddingChangeConsumerTest {

  private EmbeddingService embeddingService;
  private AggregatedArticleRepository articleRepository;
  private AggregatedEventRepository eventRepository;
  private EmbeddingChangeConsumer consumer;

  @BeforeEach
  void setUp() {
    embeddingService = mock(EmbeddingService.class);
    articleRepository = mock(AggregatedArticleRepository.class);
    eventRepository = mock(AggregatedEventRepository.class);
    consumer = new EmbeddingChangeConsumer(
        embeddingService,
        mock(BlogRepository.class),
        mock(JobRepository.class),
        mock(SkillRepository.class),
        mock(SkillGroupRepository.class),
        articleRepository,
        eventRepository);
  }

  @Test
  void deletedArticleRemovesTheNewsPrefixedVectors() {
    consumer.handleContentChange(event(EventType.DELETED, ContentType.AGGREGATED_ARTICLE, "a1"));

    verify(embeddingService).removeContent("news_a1");
    verify(embeddingService, never()).removeContent("a1");
  }

  @Test
  void deletedEventRemovesTheEventPrefixedVectors() {
    consumer.handleContentChange(event(EventType.DELETED, ContentType.AGGREGATED_EVENT, "e1"));

    verify(embeddingService).removeContent("event_e1");
    verify(embeddingService, never()).removeContent("e1");
  }

  @Test
  void deletedBlogStillRemovesByItsBareId() {
    consumer.handleContentChange(event(EventType.DELETED, ContentType.BLOG, "b1"));

    verify(embeddingService).removeContent("b1");
  }

  @Test
  void updatedHiddenArticleRemovesTheNewsPrefixedVectors() {
    when(articleRepository.findById("a1")).thenReturn(Optional.of(article("a1", false)));

    consumer.handleContentChange(event(EventType.UPDATED, ContentType.AGGREGATED_ARTICLE, "a1"));

    verify(embeddingService).removeContent("news_a1");
    verify(embeddingService, never()).embedArticle(any());
  }

  @Test
  void updatedVisibleArticleIsReEmbedded() {
    AggregatedArticle visible = article("a1", true);
    when(articleRepository.findById("a1")).thenReturn(Optional.of(visible));

    consumer.handleContentChange(event(EventType.UPDATED, ContentType.AGGREGATED_ARTICLE, "a1"));

    verify(embeddingService).embedArticle(visible);
  }

  private static ContentChangeEvent event(
      final EventType type, final ContentType contentType, final String id) {
    return new ContentChangeEvent(type, contentType, id, Instant.now());
  }

  private static AggregatedArticle article(final String id, final boolean visible) {
    return new AggregatedArticle(
        id, "Title", "Source", "https://example.com", "https://example.com/" + id,
        "Summary", "Content", "Author", Instant.now(), Instant.now(), visible, null);
  }
}
