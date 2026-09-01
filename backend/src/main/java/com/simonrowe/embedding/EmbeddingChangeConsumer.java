package com.simonrowe.embedding;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.events.ContentChangeEvent;
import com.simonrowe.events.ContentChangeEvent.EventType;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import com.simonrowe.skills.SkillRepository;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingChangeConsumer {

  private static final Logger LOG =
      LoggerFactory.getLogger(EmbeddingChangeConsumer.class);

  private final EmbeddingService embeddingService;
  private final BlogRepository blogRepository;
  private final JobRepository jobRepository;
  private final SkillRepository skillRepository;
  private final SkillGroupRepository skillGroupRepository;
  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;

  public EmbeddingChangeConsumer(
      final EmbeddingService embeddingService,
      final BlogRepository blogRepository,
      final JobRepository jobRepository,
      final SkillRepository skillRepository,
      final SkillGroupRepository skillGroupRepository,
      final AggregatedArticleRepository articleRepository,
      final AggregatedEventRepository eventRepository
  ) {
    this.embeddingService = embeddingService;
    this.blogRepository = blogRepository;
    this.jobRepository = jobRepository;
    this.skillRepository = skillRepository;
    this.skillGroupRepository = skillGroupRepository;
    this.articleRepository = articleRepository;
    this.eventRepository = eventRepository;
  }

  @RetryableTopic(
      attempts = "4",
      backOff = @BackOff(delay = 1000, multiplier = 2),
      topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
      dltTopicSuffix = ".DLT"
  )
  @KafkaListener(topics = "content-changes", groupId = "embedding-indexer")
  @WithSpan
  public void handleContentChange(final ContentChangeEvent event) {
    LOG.info("Embedding consumer received: {} {} {}",
        event.eventType(), event.contentType(), event.contentId());

    if (event.eventType() == EventType.DELETED) {
      embeddingService.removeContent(event.contentId());
      LOG.info("Removed embeddings for deleted {} {}", event.contentType(),
          event.contentId());
      return;
    }

    switch (event.contentType()) {
      case BLOG -> handleBlog(event.contentId());
      case JOB -> handleJob(event.contentId());
      case SKILL -> handleSkill(event.contentId());
      case CODE_EXAMPLE -> LOG.debug("Code example embedding handled by "
          + "AdminCodeExampleController directly");
      case AGGREGATED_ARTICLE -> handleArticle(event.contentId());
      case AGGREGATED_EVENT -> handleEvent(event.contentId());
      default -> LOG.warn("Unknown content type: {}", event.contentType());
    }
  }

  private void handleBlog(final String contentId) {
    Optional<Blog> blog = blogRepository.findByIdAndPublishedTrue(contentId);
    if (blog.isPresent()) {
      embeddingService.embedBlog(blog.get());
    } else {
      embeddingService.removeContent(contentId);
      LOG.info("Blog {} not published, removed embeddings", contentId);
    }
  }

  private void handleJob(final String contentId) {
    Optional<Job> job = jobRepository.findById(contentId);
    if (job.isPresent()) {
      embeddingService.embedJob(job.get());
    } else {
      embeddingService.removeContent(contentId);
    }
  }

  private void handleSkill(final String contentId) {
    for (SkillGroup group : skillGroupRepository.findAllByOrderByDisplayOrderAsc()) {
      if (group.skills() != null && group.skills().contains(contentId)) {
        Optional<Skill> skill = skillRepository.findById(contentId);
        if (skill.isPresent()) {
          embeddingService.embedSkill(skill.get(), group);
        } else {
          embeddingService.removeContent(contentId);
        }
        return;
      }
    }
    embeddingService.removeContent(contentId);
  }

  private void handleArticle(final String contentId) {
    Optional<AggregatedArticle> article = articleRepository.findById(contentId);
    if (article.isPresent() && article.get().visible()) {
      embeddingService.embedArticle(article.get());
    } else {
      embeddingService.removeContent("news_" + contentId);
      LOG.info("Article {} not found or hidden, removed embeddings", contentId);
    }
  }

  private void handleEvent(final String contentId) {
    Optional<AggregatedEvent> event = eventRepository.findById(contentId);
    if (event.isPresent() && event.get().visible()) {
      embeddingService.embedEvent(event.get());
    } else {
      embeddingService.removeContent("event_" + contentId);
      LOG.info("Event {} not found or hidden, removed embeddings", contentId);
    }
  }
}
