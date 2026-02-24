package com.simonrowe.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.simonrowe.search.elasticsearch.BlogSearchDocument;
import com.simonrowe.search.elasticsearch.ElasticsearchConfig;
import com.simonrowe.search.elasticsearch.SiteSearchDocument;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

  private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);
  private static final int MIN_QUERY_LENGTH = 2;

  private final ElasticsearchClient client;
  private final int maxResultsPerGroup;
  private final int maxBlogResults;
  private final int maxQueryLength;

  public SearchService(
      final ElasticsearchClient client,
      @Value("${search.site.max-results-per-group:5}") final int maxResultsPerGroup,
      @Value("${search.blog.max-results:20}") final int maxBlogResults,
      @Value("${search.query.max-length:200}") final int maxQueryLength
  ) {
    this.client = client;
    this.maxResultsPerGroup = maxResultsPerGroup;
    this.maxBlogResults = maxBlogResults;
    this.maxQueryLength = maxQueryLength;
  }

  public GroupedSearchResponse siteSearch(final String query) {
    String sanitized = sanitizeQuery(query);
    if (sanitized.length() < MIN_QUERY_LENGTH) {
      return new GroupedSearchResponse(List.of(), List.of(), List.of());
    }

    try {
      int totalSize = maxResultsPerGroup * 3;
      SearchResponse<SiteSearchDocument> response = client.search(s -> s
              .index(ElasticsearchConfig.SITE_SEARCH_INDEX)
              .size(totalSize)
              .query(q -> q
                  .multiMatch(mm -> mm
                      .query(sanitized)
                      .fields("name", "shortDescription", "longDescription")
                      .type(co.elastic.clients.elasticsearch._types.query_dsl
                          .TextQueryType.BestFields))),
          SiteSearchDocument.class);

      Map<String, List<SiteSearchDocument>> grouped = response.hits().hits().stream()
          .map(Hit::source)
          .filter(doc -> doc != null)
          .collect(Collectors.groupingBy(SiteSearchDocument::type));

      List<SearchResult> blogs = toSearchResults(grouped.getOrDefault("blog", List.of()));
      List<SearchResult> jobs = toSearchResults(grouped.getOrDefault("job", List.of()));
      List<SearchResult> skills = toSearchResults(grouped.getOrDefault("skill", List.of()));

      return new GroupedSearchResponse(blogs, jobs, skills);
    } catch (IOException e) {
      LOG.error("Site search failed for query: {}", sanitized, e);
      return new GroupedSearchResponse(List.of(), List.of(), List.of());
    }
  }

  public List<BlogSearchResult> blogSearch(final String query) {
    String sanitized = sanitizeQuery(query);
    if (sanitized.length() < MIN_QUERY_LENGTH) {
      return List.of();
    }

    try {
      SearchResponse<BlogSearchDocument> response = client.search(s -> s
              .index(ElasticsearchConfig.BLOG_SEARCH_INDEX)
              .size(maxBlogResults)
              .query(q -> q
                  .multiMatch(mm -> mm
                      .query(sanitized)
                      .fields("title^3", "tags^2", "shortDescription^2", "content", "skills")
                      .type(co.elastic.clients.elasticsearch._types.query_dsl
                          .TextQueryType.BestFields))),
          BlogSearchDocument.class);

      return response.hits().hits().stream()
          .map(Hit::source)
          .filter(doc -> doc != null)
          .map(doc -> new BlogSearchResult(
              doc.title(),
              doc.shortDescription(),
              doc.image(),
              doc.publishedDate(),
              doc.url()))
          .toList();
    } catch (IOException e) {
      LOG.error("Blog search failed for query: {}", sanitized, e);
      return List.of();
    }
  }

  private List<SearchResult> toSearchResults(final List<SiteSearchDocument> documents) {
    return documents.stream()
        .limit(maxResultsPerGroup)
        .map(doc -> new SearchResult(doc.name(), doc.image(), doc.url()))
        .toList();
  }

  private String sanitizeQuery(final String query) {
    if (query == null) {
      return "";
    }
    String trimmed = query.trim();
    if (trimmed.length() > maxQueryLength) {
      return trimmed.substring(0, maxQueryLength);
    }
    return trimmed;
  }
}
