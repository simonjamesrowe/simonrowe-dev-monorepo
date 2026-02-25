package com.simonrowe.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Tag;
import com.simonrowe.common.Image;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.search.elasticsearch.BlogSearchDocument;
import com.simonrowe.search.elasticsearch.SiteSearchDocument;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroupRepository;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IndexServiceTest {

  private ElasticsearchClient esClient;
  private IndexService indexService;

  @BeforeEach
  void setUp() {
    esClient = mock(ElasticsearchClient.class);
    BlogRepository blogRepository = mock(BlogRepository.class);
    JobRepository jobRepository = mock(JobRepository.class);
    SkillGroupRepository skillGroupRepository = mock(SkillGroupRepository.class);
    indexService = new IndexService(
        esClient, blogRepository, jobRepository, skillGroupRepository);
  }

  @Test
  void bulkIndexSiteDocumentsEmptyListDoesNotCallBulk() throws Exception {
    indexService.bulkIndexSiteDocuments(List.of());
    verify(esClient, never()).bulk(any(BulkRequest.class));
  }

  @Test
  void bulkIndexBlogDocumentsEmptyListDoesNotCallBulk() throws Exception {
    indexService.bulkIndexBlogDocuments(List.of());
    verify(esClient, never()).bulk(any(BulkRequest.class));
  }

  @Test
  void blogToSiteDocumentTransformsCorrectly() {
    Blog blog = new Blog(
        "blog1", "Test Blog", "Short desc", "Full content", true,
        "/images/blog.jpg", Instant.now(), Instant.now(), List.of(), List.of());

    SiteSearchDocument doc = indexService.blogToSiteDocument(blog);

    assertThat(doc.id()).isEqualTo("blog1");
    assertThat(doc.name()).isEqualTo("Test Blog");
    assertThat(doc.type()).isEqualTo("blog");
    assertThat(doc.shortDescription()).isEqualTo("Short desc");
    assertThat(doc.longDescription()).isNull();
    assertThat(doc.image()).isEqualTo("/images/blog.jpg");
    assertThat(doc.url()).isEqualTo("/blogs/blog1");
  }

  @Test
  void jobToSiteDocumentTransformsCorrectly() {
    Image companyImage = new Image(
        "/images/acme.png", "acme", 100, 100, "image/png", null);
    Job job = new Job(
        "job1", "Senior Developer", "Acme Corp", "https://acme.com",
        companyImage, "2020-01", "2023-06", "London",
        "Short desc", "Long desc", false, true, List.of());

    SiteSearchDocument doc = indexService.jobToSiteDocument(job);

    assertThat(doc.id()).isEqualTo("job1");
    assertThat(doc.name()).isEqualTo("Senior Developer");
    assertThat(doc.type()).isEqualTo("job");
    assertThat(doc.shortDescription()).isEqualTo("Short desc");
    assertThat(doc.longDescription()).isEqualTo("Long desc");
    assertThat(doc.image()).isEqualTo("/images/acme.png");
    assertThat(doc.url()).isEqualTo("/jobs/job1");
  }

  @Test
  void jobToSiteDocumentNullImageReturnsNullImage() {
    Job job = new Job(
        "job1", "Dev", "Co", "https://co.com", null,
        "2020-01", null, "London", "Desc", "Long",
        false, true, List.of());

    SiteSearchDocument doc = indexService.jobToSiteDocument(job);
    assertThat(doc.image()).isNull();
  }

  @Test
  void skillToSiteDocumentTransformsCorrectly() {
    Image skillImage = new Image(
        "/images/java.png", "java", 50, 50, "image/png", null);
    Skill skill = new Skill("s1", "Java", 4.5, 1, "Java language", skillImage);

    SiteSearchDocument doc = indexService.skillToSiteDocument(skill, "g1");

    assertThat(doc.id()).isEqualTo("g1_s1");
    assertThat(doc.name()).isEqualTo("Java");
    assertThat(doc.type()).isEqualTo("skill");
    assertThat(doc.shortDescription()).isEqualTo("Java language");
    assertThat(doc.longDescription()).isNull();
    assertThat(doc.image()).isEqualTo("/images/java.png");
    assertThat(doc.url()).isEqualTo("/skills-groups/g1");
  }

  @Test
  void skillToSiteDocumentNullImageReturnsNullImage() {
    Skill skill = new Skill("s1", "Java", 4.5, 1, "Java language", null);

    SiteSearchDocument doc = indexService.skillToSiteDocument(skill, "g1");

    assertThat(doc.image()).isNull();
  }

  @Test
  void blogToBlogDocumentTransformsCorrectly() {
    Tag tag1 = new Tag("t1", "Spring");
    Tag tag2 = new Tag("t2", "Java");
    com.simonrowe.blog.Skill blogSkill =
        new com.simonrowe.blog.Skill("bs1", "Spring Boot");
    Instant created = Instant.parse("2025-06-15T10:00:00Z");

    Blog blog = new Blog(
        "blog1", "Spring Guide", "A guide to Spring",
        "Full markdown content", true, "/images/spring.jpg",
        created, Instant.now(),
        List.of(tag1, tag2), List.of(blogSkill));

    BlogSearchDocument doc = indexService.blogToBlogDocument(blog);

    assertThat(doc.id()).isEqualTo("blog1");
    assertThat(doc.title()).isEqualTo("Spring Guide");
    assertThat(doc.shortDescription()).isEqualTo("A guide to Spring");
    assertThat(doc.content()).isEqualTo("Full markdown content");
    assertThat(doc.tags()).containsExactly("Spring", "Java");
    assertThat(doc.skills()).containsExactly("Spring Boot");
    assertThat(doc.image()).isEqualTo("/images/spring.jpg");
    assertThat(doc.publishedDate()).isEqualTo(created);
    assertThat(doc.url()).isEqualTo("/blogs/blog1");
  }

  @Test
  void blogToBlogDocumentNullTagsReturnsEmptyList() {
    Blog blog = new Blog(
        "blog1", "Title", "Desc", "Content", true,
        null, Instant.now(), Instant.now(), null, null);

    BlogSearchDocument doc = indexService.blogToBlogDocument(blog);

    assertThat(doc.tags()).isEmpty();
    assertThat(doc.skills()).isEmpty();
  }

  @SuppressWarnings("unchecked")
  @Test
  void indexSiteDocumentCallsClient() throws Exception {
    IndexResponse mockResponse = mock(IndexResponse.class);
    when(esClient.index(any(Function.class))).thenReturn(mockResponse);

    SiteSearchDocument doc = new SiteSearchDocument(
        "id1", "Name", "blog", "Desc", null, null, "/url");
    indexService.indexSiteDocument(doc);

    verify(esClient).index(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void indexBlogDocumentCallsClient() throws Exception {
    IndexResponse mockResponse = mock(IndexResponse.class);
    when(esClient.index(any(Function.class))).thenReturn(mockResponse);

    BlogSearchDocument doc = new BlogSearchDocument(
        "id1", "Title", "Desc", "Content",
        List.of(), List.of(), null, Instant.now(), "/url");
    indexService.indexBlogDocument(doc);

    verify(esClient).index(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void deleteSiteDocumentCallsClient() throws Exception {
    DeleteResponse mockResponse = mock(DeleteResponse.class);
    when(esClient.delete(any(Function.class))).thenReturn(mockResponse);

    indexService.deleteSiteDocument("id1");

    verify(esClient).delete(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void deleteBlogDocumentCallsClient() throws Exception {
    DeleteResponse mockResponse = mock(DeleteResponse.class);
    when(esClient.delete(any(Function.class))).thenReturn(mockResponse);

    indexService.deleteBlogDocument("id1");

    verify(esClient).delete(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void indexBlogContentIndexesBothIndices() throws Exception {
    IndexResponse mockResponse = mock(IndexResponse.class);
    when(esClient.index(any(Function.class))).thenReturn(mockResponse);

    Blog blog = new Blog(
        "b1", "Title", "Desc", "Content", true,
        "/img.jpg", Instant.now(), Instant.now(), List.of(), List.of());

    indexService.indexBlogContent(blog);

    verify(esClient, times(2)).index(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void deleteBlogContentDeletesFromBothIndices() throws Exception {
    DeleteResponse mockResponse = mock(DeleteResponse.class);
    when(esClient.delete(any(Function.class))).thenReturn(mockResponse);

    indexService.deleteBlogContent("b1");

    verify(esClient, times(2)).delete(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void indexJobContentIndexesSiteIndex() throws Exception {
    IndexResponse mockResponse = mock(IndexResponse.class);
    when(esClient.index(any(Function.class))).thenReturn(mockResponse);

    Job job = new Job(
        "j1", "Dev", "Co", "https://co.com", null,
        "2020-01", null, "London", "Desc", "Long",
        false, true, List.of());

    indexService.indexJobContent(job);

    verify(esClient).index(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void deleteJobContentDeletesFromSiteIndex() throws Exception {
    DeleteResponse mockResponse = mock(DeleteResponse.class);
    when(esClient.delete(any(Function.class))).thenReturn(mockResponse);

    indexService.deleteJobContent("j1");

    verify(esClient).delete(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void indexSkillContentIndexesSiteIndex() throws Exception {
    IndexResponse mockResponse = mock(IndexResponse.class);
    when(esClient.index(any(Function.class))).thenReturn(mockResponse);

    Skill skill = new Skill("s1", "Java", 4.0, 1, "Java", null);

    indexService.indexSkillContent(skill, "g1");

    verify(esClient).index(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void deleteSkillContentDeletesFromSiteIndex() throws Exception {
    DeleteResponse mockResponse = mock(DeleteResponse.class);
    when(esClient.delete(any(Function.class))).thenReturn(mockResponse);

    indexService.deleteSkillContent("s1");

    verify(esClient).delete(any(Function.class));
  }

  @Test
  void bulkIndexSiteDocumentsCallsBulk() throws Exception {
    BulkResponse mockResponse = mock(BulkResponse.class);
    when(mockResponse.errors()).thenReturn(false);
    when(esClient.bulk(any(BulkRequest.class))).thenReturn(mockResponse);

    SiteSearchDocument doc = new SiteSearchDocument(
        "id1", "Name", "blog", "Desc", null, null, "/url");
    indexService.bulkIndexSiteDocuments(List.of(doc));

    verify(esClient).bulk(any(BulkRequest.class));
  }

  @Test
  void bulkIndexBlogDocumentsCallsBulk() throws Exception {
    BulkResponse mockResponse = mock(BulkResponse.class);
    when(mockResponse.errors()).thenReturn(false);
    when(esClient.bulk(any(BulkRequest.class))).thenReturn(mockResponse);

    BlogSearchDocument doc = new BlogSearchDocument(
        "id1", "Title", "Desc", "Content",
        List.of(), List.of(), null, Instant.now(), "/url");
    indexService.bulkIndexBlogDocuments(List.of(doc));

    verify(esClient).bulk(any(BulkRequest.class));
  }
}
