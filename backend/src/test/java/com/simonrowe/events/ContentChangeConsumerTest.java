package com.simonrowe.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangeEvent.EventType;
import com.simonrowe.search.IndexService;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContentChangeConsumerTest {

  private IndexService indexService;
  private BlogRepository blogRepository;
  private JobRepository jobRepository;
  private SkillGroupRepository skillGroupRepository;
  private ContentChangeConsumer consumer;

  @BeforeEach
  void setUp() {
    indexService = mock(IndexService.class);
    blogRepository = mock(BlogRepository.class);
    jobRepository = mock(JobRepository.class);
    skillGroupRepository = mock(SkillGroupRepository.class);
    consumer = new ContentChangeConsumer(
        indexService, blogRepository, jobRepository, skillGroupRepository);
  }

  @Test
  void handleBlogCreatedIndexesPublishedBlog() throws Exception {
    Blog blog = new Blog(
        "b1", "Title", "Desc", "Content", true,
        "/img.jpg", Instant.now(), Instant.now(), List.of(), List.of());
    when(blogRepository.findByIdAndPublishedTrue("b1")).thenReturn(Optional.of(blog));

    ContentChangeEvent event = new ContentChangeEvent(
        EventType.CREATED, ContentType.BLOG, "b1", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).indexBlogContent(blog);
  }

  @Test
  void handleBlogCreatedDeletesUnpublishedBlog() throws Exception {
    when(blogRepository.findByIdAndPublishedTrue("b2")).thenReturn(Optional.empty());

    ContentChangeEvent event = new ContentChangeEvent(
        EventType.CREATED, ContentType.BLOG, "b2", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).deleteBlogContent("b2");
  }

  @Test
  void handleBlogUpdatedIndexesPublishedBlog() throws Exception {
    Blog blog = new Blog(
        "b1", "Updated", "Desc", "Content", true,
        "/img.jpg", Instant.now(), Instant.now(), List.of(), List.of());
    when(blogRepository.findByIdAndPublishedTrue("b1")).thenReturn(Optional.of(blog));

    ContentChangeEvent event = new ContentChangeEvent(
        EventType.UPDATED, ContentType.BLOG, "b1", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).indexBlogContent(blog);
  }

  @Test
  void handleBlogDeletedRemovesFromIndex() throws Exception {
    ContentChangeEvent event = new ContentChangeEvent(
        EventType.DELETED, ContentType.BLOG, "b1", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).deleteBlogContent("b1");
  }

  @Test
  void handleJobCreatedIndexesJob() throws Exception {
    Job job = new Job(
        "j1", "Dev", "Co", "https://co.com", null,
        "2020-01", null, "London", "Desc", "Long",
        false, true, List.of());
    when(jobRepository.findById("j1")).thenReturn(Optional.of(job));

    ContentChangeEvent event = new ContentChangeEvent(
        EventType.CREATED, ContentType.JOB, "j1", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).indexJobContent(job);
  }

  @Test
  void handleJobCreatedDeletesWhenNotFound() throws Exception {
    when(jobRepository.findById("j2")).thenReturn(Optional.empty());

    ContentChangeEvent event = new ContentChangeEvent(
        EventType.CREATED, ContentType.JOB, "j2", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).deleteJobContent("j2");
  }

  @Test
  void handleJobDeletedRemovesFromIndex() throws Exception {
    ContentChangeEvent event = new ContentChangeEvent(
        EventType.DELETED, ContentType.JOB, "j1", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).deleteJobContent("j1");
  }

  @Test
  void handleSkillCreatedIndexesMatchingSkill() throws Exception {
    Skill skill = new Skill("s1", "Java", 4.0, 1, "Java language", null);
    SkillGroup group = new SkillGroup(
        "g1", "Languages", "Programming languages",
        4.0, 1, null, List.of(skill));
    when(skillGroupRepository.findAllByOrderByDisplayOrderAsc())
        .thenReturn(List.of(group));

    ContentChangeEvent event = new ContentChangeEvent(
        EventType.CREATED, ContentType.SKILL, "s1", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).indexSkillContent(skill, "g1");
  }

  @Test
  void handleSkillCreatedByGroupIdIndexesSkill() throws Exception {
    Skill skill = new Skill("s1", "Java", 4.0, 1, "Java language", null);
    SkillGroup group = new SkillGroup(
        "g1", "Languages", "Programming languages",
        4.0, 1, null, List.of(skill));
    when(skillGroupRepository.findAllByOrderByDisplayOrderAsc())
        .thenReturn(List.of(group));

    ContentChangeEvent event = new ContentChangeEvent(
        EventType.CREATED, ContentType.SKILL, "g1", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).indexSkillContent(skill, "g1");
  }

  @Test
  void handleSkillCreatedDeletesWhenNotFound() throws Exception {
    SkillGroup group = new SkillGroup(
        "g1", "Languages", "Programming languages",
        4.0, 1, null, List.of());
    when(skillGroupRepository.findAllByOrderByDisplayOrderAsc())
        .thenReturn(List.of(group));

    ContentChangeEvent event = new ContentChangeEvent(
        EventType.CREATED, ContentType.SKILL, "unknown", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).deleteSkillContent("unknown");
  }

  @Test
  void handleSkillDeletedRemovesFromIndex() throws Exception {
    ContentChangeEvent event = new ContentChangeEvent(
        EventType.DELETED, ContentType.SKILL, "s1", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).deleteSkillContent("s1");
  }

  @Test
  void handleSkillCreatedSkipsGroupWithNullSkills() throws Exception {
    SkillGroup groupWithNull = new SkillGroup(
        "g1", "Empty", "No skills", 1.0, 1, null, null);
    Skill skill = new Skill("s1", "Java", 4.0, 1, "Java", null);
    SkillGroup groupWithSkill = new SkillGroup(
        "g2", "Languages", "Programming", 4.0, 2, null, List.of(skill));
    when(skillGroupRepository.findAllByOrderByDisplayOrderAsc())
        .thenReturn(List.of(groupWithNull, groupWithSkill));

    ContentChangeEvent event = new ContentChangeEvent(
        EventType.CREATED, ContentType.SKILL, "s1", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).indexSkillContent(skill, "g2");
  }

  @Test
  void handleJobUpdatedIndexesJob() throws Exception {
    Job job = new Job(
        "j1", "Senior Dev", "Co", "https://co.com", null,
        "2020-01", null, "London", "Updated", "Long updated",
        false, true, List.of());
    when(jobRepository.findById("j1")).thenReturn(Optional.of(job));

    ContentChangeEvent event = new ContentChangeEvent(
        EventType.UPDATED, ContentType.JOB, "j1", Instant.now());
    consumer.handleContentChange(event);

    verify(indexService).indexJobContent(job);
  }
}
