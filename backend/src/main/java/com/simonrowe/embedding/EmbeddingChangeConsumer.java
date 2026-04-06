package com.simonrowe.embedding;

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
import org.springframework.retry.annotation.Backoff;
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

  public EmbeddingChangeConsumer(
      final EmbeddingService embeddingService,
      final BlogRepository blogRepository,
      final JobRepository jobRepository,
      final SkillRepository skillRepository,
      final SkillGroupRepository skillGroupRepository
  ) {
    this.embeddingService = embeddingService;
    this.blogRepository = blogRepository;
    this.jobRepository = jobRepository;
    this.skillRepository = skillRepository;
    this.skillGroupRepository = skillGroupRepository;
  }

  @RetryableTopic(
      attempts = "4",
      backoff = @Backoff(delay = 1000, multiplier = 2),
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
}
