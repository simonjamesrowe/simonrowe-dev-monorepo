package com.simonrowe.events;

import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangeEvent.EventType;
import com.simonrowe.search.IndexService;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
public class ContentChangeConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(ContentChangeConsumer.class);

  private final IndexService indexService;
  private final BlogRepository blogRepository;
  private final JobRepository jobRepository;
  private final SkillGroupRepository skillGroupRepository;

  public ContentChangeConsumer(
      final IndexService indexService,
      final BlogRepository blogRepository,
      final JobRepository jobRepository,
      final SkillGroupRepository skillGroupRepository
  ) {
    this.indexService = indexService;
    this.blogRepository = blogRepository;
    this.jobRepository = jobRepository;
    this.skillGroupRepository = skillGroupRepository;
  }

  @RetryableTopic(
      attempts = "4",
      backoff = @Backoff(delay = 1000, multiplier = 2),
      topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
      dltTopicSuffix = ".DLT"
  )
  @KafkaListener(topics = "content-changes", groupId = "search-indexer")
  public void handleContentChange(final ContentChangeEvent event) throws IOException {
    LOG.info("Received content change event: {} {} {}",
        event.eventType(), event.contentType(), event.contentId());

    if (event.eventType() == EventType.DELETED) {
      handleDelete(event);
    } else {
      handleCreateOrUpdate(event);
    }
  }

  private void handleCreateOrUpdate(final ContentChangeEvent event) throws IOException {
    switch (event.contentType()) {
      case BLOG -> handleBlogCreateOrUpdate(event.contentId());
      case JOB -> handleJobCreateOrUpdate(event.contentId());
      case SKILL -> handleSkillCreateOrUpdate(event.contentId());
      default -> LOG.warn("Unknown content type: {}", event.contentType());
    }
  }

  private void handleDelete(final ContentChangeEvent event) throws IOException {
    switch (event.contentType()) {
      case BLOG -> indexService.deleteBlogContent(event.contentId());
      case JOB -> indexService.deleteJobContent(event.contentId());
      case SKILL -> indexService.deleteSkillContent(event.contentId());
      default -> LOG.warn("Unknown content type for delete: {}", event.contentType());
    }
    LOG.info("Deleted {} {} from search index", event.contentType(), event.contentId());
  }

  private void handleBlogCreateOrUpdate(final String contentId) throws IOException {
    Optional<Blog> blog = blogRepository.findByIdAndPublishedTrue(contentId);
    if (blog.isPresent()) {
      indexService.indexBlogContent(blog.get());
      LOG.info("Indexed blog {} in search indices", contentId);
    } else {
      indexService.deleteBlogContent(contentId);
      LOG.info("Blog {} not found or not published, removed from search indices", contentId);
    }
  }

  private void handleJobCreateOrUpdate(final String contentId) throws IOException {
    Optional<Job> job = jobRepository.findById(contentId);
    if (job.isPresent()) {
      indexService.indexJobContent(job.get());
      LOG.info("Indexed job {} in search index", contentId);
    } else {
      indexService.deleteJobContent(contentId);
      LOG.info("Job {} not found, removed from search index", contentId);
    }
  }

  private void handleSkillCreateOrUpdate(final String contentId) throws IOException {
    for (SkillGroup group : skillGroupRepository.findAllByOrderByDisplayOrderAsc()) {
      if (group.skills() == null) {
        continue;
      }
      for (com.simonrowe.skills.Skill skill : group.skills()) {
        if (contentId.equals(skill.id()) || contentId.equals(group.id())) {
          indexService.indexSkillContent(skill, group.id());
          LOG.info("Indexed skill {} from group {} in search index", skill.id(), group.id());
          return;
        }
      }
    }
    indexService.deleteSkillContent(contentId);
    LOG.info("Skill {} not found, removed from search index", contentId);
  }
}
