package com.simonrowe.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchServiceTest {

  private SearchService searchService;

  @BeforeEach
  void setUp() {
    ElasticsearchClient esClient = mock(ElasticsearchClient.class);
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
}
