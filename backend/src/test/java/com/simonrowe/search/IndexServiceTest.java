package com.simonrowe.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.common.Image;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.search.elasticsearch.SiteSearchDocument;
import com.simonrowe.skills.SkillGroupRepository;
import java.time.Instant;
import java.util.List;
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
    assertThat(doc.url()).isEqualTo("/employment");
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
}
