package com.simonrowe.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.util.ObjectBuilder;
import com.simonrowe.media.MediaVariantResolver;
import com.simonrowe.search.elasticsearch.BlogSearchDocument;
import com.simonrowe.search.elasticsearch.SiteSearchDocument;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchServiceTest {

  private ElasticsearchClient esClient;
  private SearchService searchService;
  private MediaVariantResolver mediaVariantResolver;

  @BeforeEach
  void setUp() {
    esClient = mock(ElasticsearchClient.class);
    mediaVariantResolver = mock(MediaVariantResolver.class);
    when(mediaVariantResolver.resolvePath(any(), any(String[].class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    searchService = new SearchService(esClient, 5, 20, 200, mediaVariantResolver);
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
        null, null, "/img.jpg", "/blogs/java", null);
    SiteSearchDocument jobDoc = new SiteSearchDocument(
        "j1", "Java Dev", "job", "Java developer",
        "Long desc", "Acme Corp", null, "/employment", null);
    SiteSearchDocument skillDoc = new SiteSearchDocument(
        "s1", "Java", "skill", "Java language",
        null, null, "/img/java.png", "/skills", null);

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
    SearchService shortMaxService = new SearchService(
        esClient, 5, 20, 10, mediaVariantResolver);

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

  /**
   * The query actually put on the wire, rather than what comes back — the relevance
   * problem these guard against is decided entirely by the request.
   *
   * <p>They exist because a mistyped term used to match nothing and leave the query
   * silently degraded to whichever of the remaining words did match: searching two words
   * with one of them misspelled returned a page of plausible-looking results with the one
   * right answer nowhere in it, which reads as "the thing was never indexed".
   */
  @Nested
  class TheQuerySent {

    @SuppressWarnings("unchecked")
    private SearchRequest capture() throws Exception {
      ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
          ArgumentCaptor.forClass(Function.class);
      verify(esClient).search(captor.capture(), any(Class.class));
      return SearchRequest.of(builder -> captor.getValue().apply(builder));
    }

    @SuppressWarnings("unchecked")
    private void stubEmptyResponse() throws Exception {
      HitsMetadata<SiteSearchDocument> hits = mock(HitsMetadata.class);
      when(hits.hits()).thenReturn(List.of());
      SearchResponse<SiteSearchDocument> response = mock(SearchResponse.class);
      when(response.hits()).thenReturn(hits);
      when(esClient.search(any(Function.class), any(Class.class))).thenReturn(response);
    }

    @Test
    void toleratesTyposAndWeightsTheTitleOnTheSiteSearch() throws Exception {
      stubEmptyResponse();

      searchService.siteSearch("AI SLDC");

      MultiMatchQuery query = capture().query().multiMatch();
      assertThat(query.fuzziness()).isEqualTo("AUTO");
      assertThat(query.prefixLength()).isEqualTo(1);
      assertThat(query.fields()).containsExactly(
          "name^3", "shortDescription^2", "longDescription", "company");
    }

    @Test
    void toleratesTyposOnTheBlogSearch() throws Exception {
      stubEmptyResponse();

      searchService.blogSearch("sprign boot");

      MultiMatchQuery query = capture().query().multiMatch();
      assertThat(query.fuzziness()).isEqualTo("AUTO");
      assertThat(query.prefixLength()).isEqualTo(1);
      assertThat(query.fields()).startsWith("title^3");
    }

    /**
     * The by-type search wraps its match in a bool so it can filter on type. That wrapping
     * is exactly where a shared query shape gets forgotten.
     */
    @Test
    void toleratesTyposOnTheByTypeSearch() throws Exception {
      stubEmptyResponse();

      searchService.searchByType("AI SLDC", "news");

      MultiMatchQuery query = capture().query().bool().must().getFirst().multiMatch();
      assertThat(query.fuzziness()).isEqualTo("AUTO");
      assertThat(query.prefixLength()).isEqualTo(1);
      assertThat(query.fields()).startsWith("name^3");
    }
  }
}
