package com.simonrowe.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.simonrowe.search.elasticsearch.BlogSearchDocument;
import com.simonrowe.search.elasticsearch.SiteSearchDocument;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchServiceTest {

  private ElasticsearchClient esClient;
  private SearchService searchService;

  @BeforeEach
  void setUp() {
    esClient = mock(ElasticsearchClient.class);
    searchService = new SearchService(esClient, 5, 20, 200);
  }

  @Test
  void siteSearchShortQueryReturnsEmpty() {
    GroupedSearchResponse response = searchService.siteSearch("a");
    assertThat(response.blogs()).isEmpty();
    assertThat(response.jobs()).isEmpty();
    assertThat(response.skills()).isEmpty();
  }

  @Test
  void siteSearchNullQueryReturnsEmpty() {
    GroupedSearchResponse response = searchService.siteSearch(null);
    assertThat(response.blogs()).isEmpty();
    assertThat(response.jobs()).isEmpty();
    assertThat(response.skills()).isEmpty();
  }

  @Test
  void siteSearchEmptyQueryReturnsEmpty() {
    GroupedSearchResponse response = searchService.siteSearch("");
    assertThat(response.blogs()).isEmpty();
    assertThat(response.jobs()).isEmpty();
    assertThat(response.skills()).isEmpty();
  }

  @Test
  void blogSearchShortQueryReturnsEmpty() {
    List<BlogSearchResult> results = searchService.blogSearch("x");
    assertThat(results).isEmpty();
  }

  @Test
  void blogSearchNullQueryReturnsEmpty() {
    List<BlogSearchResult> results = searchService.blogSearch(null);
    assertThat(results).isEmpty();
  }

  @Test
  void blogSearchEmptyQueryReturnsEmpty() {
    List<BlogSearchResult> results = searchService.blogSearch("");
    assertThat(results).isEmpty();
  }

  @SuppressWarnings("unchecked")
  @Test
  void siteSearchReturnsGroupedResults() throws Exception {
    SiteSearchDocument blogDoc = new SiteSearchDocument(
        "b1", "Java Blog", "blog", "A Java blog",
        null, "/img.jpg", "/blogs/java");
    SiteSearchDocument jobDoc = new SiteSearchDocument(
        "j1", "Java Dev", "job", "Java developer",
        "Long desc", null, "/employment");
    SiteSearchDocument skillDoc = new SiteSearchDocument(
        "s1", "Java", "skill", "Java language",
        null, "/img/java.png", "/skills");

    Hit<SiteSearchDocument> blogHit = mock(Hit.class);
    when(blogHit.source()).thenReturn(blogDoc);
    Hit<SiteSearchDocument> jobHit = mock(Hit.class);
    when(jobHit.source()).thenReturn(jobDoc);
    Hit<SiteSearchDocument> skillHit = mock(Hit.class);
    when(skillHit.source()).thenReturn(skillDoc);

    HitsMetadata<SiteSearchDocument> hits = mock(HitsMetadata.class);
    when(hits.hits()).thenReturn(List.of(blogHit, jobHit, skillHit));

    SearchResponse<SiteSearchDocument> response = mock(SearchResponse.class);
    when(response.hits()).thenReturn(hits);
    when(esClient.search(any(Function.class), any(Class.class)))
        .thenReturn(response);

    GroupedSearchResponse result = searchService.siteSearch("java");

    assertThat(result.blogs()).hasSize(1);
    assertThat(result.blogs().getFirst().name()).isEqualTo("Java Blog");
    assertThat(result.jobs()).hasSize(1);
    assertThat(result.jobs().getFirst().name()).isEqualTo("Java Dev");
    assertThat(result.skills()).hasSize(1);
    assertThat(result.skills().getFirst().name()).isEqualTo("Java");
  }

  @SuppressWarnings("unchecked")
  @Test
  void siteSearchHandlesIoException() throws Exception {
    when(esClient.search(any(Function.class), any(Class.class)))
        .thenThrow(new IOException("Connection refused"));

    GroupedSearchResponse result = searchService.siteSearch("java");

    assertThat(result.blogs()).isEmpty();
    assertThat(result.jobs()).isEmpty();
    assertThat(result.skills()).isEmpty();
  }

  @SuppressWarnings("unchecked")
  @Test
  void blogSearchReturnsResults() throws Exception {
    Instant published = Instant.parse("2025-06-15T10:00:00Z");
    BlogSearchDocument blogDoc = new BlogSearchDocument(
        "b1", "Spring Boot Guide", "A guide to Spring",
        "Full content", List.of("spring"), List.of("Java"),
        "/img/spring.jpg", published, "/blogs/spring-boot");

    Hit<BlogSearchDocument> hit = mock(Hit.class);
    when(hit.source()).thenReturn(blogDoc);

    HitsMetadata<BlogSearchDocument> hits = mock(HitsMetadata.class);
    when(hits.hits()).thenReturn(List.of(hit));

    SearchResponse<BlogSearchDocument> response = mock(SearchResponse.class);
    when(response.hits()).thenReturn(hits);
    when(esClient.search(any(Function.class), any(Class.class)))
        .thenReturn(response);

    List<BlogSearchResult> results = searchService.blogSearch("spring");

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().title()).isEqualTo("Spring Boot Guide");
    assertThat(results.getFirst().shortDescription())
        .isEqualTo("A guide to Spring");
    assertThat(results.getFirst().image()).isEqualTo("/img/spring.jpg");
    assertThat(results.getFirst().publishedDate()).isEqualTo(published);
    assertThat(results.getFirst().url()).isEqualTo("/blogs/spring-boot");
  }

  @SuppressWarnings("unchecked")
  @Test
  void blogSearchHandlesIoException() throws Exception {
    when(esClient.search(any(Function.class), any(Class.class)))
        .thenThrow(new IOException("Connection refused"));

    List<BlogSearchResult> results = searchService.blogSearch("spring");

    assertThat(results).isEmpty();
  }

  @SuppressWarnings("unchecked")
  @Test
  void siteSearchFiltersNullSources() throws Exception {
    Hit<SiteSearchDocument> nullHit = mock(Hit.class);
    when(nullHit.source()).thenReturn(null);

    HitsMetadata<SiteSearchDocument> hits = mock(HitsMetadata.class);
    when(hits.hits()).thenReturn(List.of(nullHit));

    SearchResponse<SiteSearchDocument> response = mock(SearchResponse.class);
    when(response.hits()).thenReturn(hits);
    when(esClient.search(any(Function.class), any(Class.class)))
        .thenReturn(response);

    GroupedSearchResponse result = searchService.siteSearch("test");

    assertThat(result.blogs()).isEmpty();
    assertThat(result.jobs()).isEmpty();
    assertThat(result.skills()).isEmpty();
  }

  @SuppressWarnings("unchecked")
  @Test
  void blogSearchFiltersNullSources() throws Exception {
    Hit<BlogSearchDocument> nullHit = mock(Hit.class);
    when(nullHit.source()).thenReturn(null);

    HitsMetadata<BlogSearchDocument> hits = mock(HitsMetadata.class);
    when(hits.hits()).thenReturn(List.of(nullHit));

    SearchResponse<BlogSearchDocument> response = mock(SearchResponse.class);
    when(response.hits()).thenReturn(hits);
    when(esClient.search(any(Function.class), any(Class.class)))
        .thenReturn(response);

    List<BlogSearchResult> results = searchService.blogSearch("test");

    assertThat(results).isEmpty();
  }

  @Test
  void siteSearchWhitespaceOnlyQueryReturnsEmpty() {
    GroupedSearchResponse response = searchService.siteSearch("   ");
    assertThat(response.blogs()).isEmpty();
  }

  @Test
  void blogSearchWhitespaceOnlyQueryReturnsEmpty() {
    List<BlogSearchResult> results = searchService.blogSearch("   ");
    assertThat(results).isEmpty();
  }

  @SuppressWarnings("unchecked")
  @Test
  void siteSearchTruncatesLongQuery() throws Exception {
    SearchService shortMaxService = new SearchService(esClient, 5, 20, 10);

    HitsMetadata<SiteSearchDocument> hits = mock(HitsMetadata.class);
    when(hits.hits()).thenReturn(List.of());

    SearchResponse<SiteSearchDocument> response = mock(SearchResponse.class);
    when(response.hits()).thenReturn(hits);
    when(esClient.search(any(Function.class), any(Class.class)))
        .thenReturn(response);

    GroupedSearchResponse result = shortMaxService.siteSearch(
        "a very long query that exceeds the max length");

    assertThat(result).isNotNull();
    assertThat(result.blogs()).isEmpty();
  }
}
