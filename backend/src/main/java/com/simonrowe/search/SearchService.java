package com.simonrowe.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.simonrowe.media.MediaVariantResolver;
import com.simonrowe.search.elasticsearch.BlogSearchDocument;
import com.simonrowe.search.elasticsearch.ElasticsearchConfig;
import com.simonrowe.search.elasticsearch.SiteSearchDocument;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

  /**
   * The site index's searchable fields, weighted so a title match beats a body match.
   *
   * <p>They all counted the same before, which is why searching for two words where only
   * one of them matched anything returned whatever happened to mention that one word most
   * often rather than the item the query was about.
   */
  private static final List<String> SITE_FIELDS =
      List.of("name^3", "shortDescription^2", "longDescription", "company");

  private static final List<String> BLOG_FIELDS =
      List.of("title^3", "tags^2", "shortDescription^2", "content", "skills");

  /**
   * Edit distance allowed per term: none below three characters, one up to five, two
   * beyond. Typing "SLDC" for "SDLC" is a transposition, which Elasticsearch's
   * Damerau-Levenshtein automaton counts as one edit.
   *
   * <p>Without this a mistyped term matches nothing at all and the query silently degrades
   * to whichever of the remaining words did match — a result set that looks like a working
   * search returning the wrong answer, rather than like a typo.
   */
  private static final String FUZZINESS = "AUTO";

  /**
   * The first character has to be right. It keeps term expansion cheap and stops a short
   * query reaching half the index.
   */
  private static final int FUZZY_PREFIX_LENGTH = 1;

  private final ElasticsearchClient client;
  private final int maxResultsPerGroup;
  private final int maxBlogResults;
  private final int maxQueryLength;
  private final MediaVariantResolver mediaVariantResolver;

  public SearchService(
      final ElasticsearchClient client,
      @Value("${search.site.max-results-per-group:5}") final int maxResultsPerGroup,
      @Value("${search.blog.max-results:20}") final int maxBlogResults,
      @Value("${search.query.max-length:200}") final int maxQueryLength,
      final MediaVariantResolver mediaVariantResolver
  ) {
    this.client = client;
    this.maxResultsPerGroup = maxResultsPerGroup;
    this.maxBlogResults = maxBlogResults;
    this.maxQueryLength = maxQueryLength;
    this.mediaVariantResolver = mediaVariantResolver;
  }

  public GroupedSearchResponse siteSearch(final String query) {
    String sanitized = sanitizeQuery(query);
    if (sanitized.length() < MIN_QUERY_LENGTH) {
      return new GroupedSearchResponse(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    try {
      int totalSize = maxResultsPerGroup * 5;
      SearchResponse<SiteSearchDocument> response = client.search(s -> s
              .index(ElasticsearchConfig.SITE_SEARCH_INDEX)
              .size(totalSize)
              .query(q -> q.multiMatch(mm -> typoTolerant(mm, sanitized, SITE_FIELDS)))
              .sort(sort -> sort.score(sc -> sc
                  .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
              .sort(sort -> sort.field(f -> f
                  .field("sortDate")
                  .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)
                  .missing("_last"))),
          SiteSearchDocument.class);

      Map<String, List<SiteSearchDocument>> grouped = response.hits().hits().stream()
          .map(Hit::source)
          .filter(doc -> doc != null)
          .collect(Collectors.groupingBy(
              SiteSearchDocument::type, LinkedHashMap::new, Collectors.toList()));

      List<SearchResult> blogs = toSearchResults(grouped.getOrDefault("blog", List.of()));
      List<SearchResult> jobs = toSearchResults(grouped.getOrDefault("job", List.of()));
      List<SearchResult> skills = toSearchResults(grouped.getOrDefault("skill", List.of()));
      List<SearchResult> news = toSearchResults(grouped.getOrDefault("news", List.of()));
      List<SearchResult> events = toSearchResults(grouped.getOrDefault("event", List.of()));

      return new GroupedSearchResponse(blogs, jobs, skills, news, events);
    } catch (IOException e) {
      LOG.error("Site search failed for query: {}", sanitized, e);
      return new GroupedSearchResponse(List.of(), List.of(), List.of(), List.of(), List.of());
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
              .query(q -> q.multiMatch(mm -> typoTolerant(mm, sanitized, BLOG_FIELDS))),
          BlogSearchDocument.class);

      return response.hits().hits().stream()
          .map(Hit::source)
          .filter(doc -> doc != null)
          .map(doc -> new BlogSearchResult(
              doc.title(),
              doc.shortDescription(),
              mediaVariantResolver.resolvePath(doc.image(), "thumbnail", "small", "medium"),
              doc.publishedDate(),
              doc.url()))
          .toList();
    } catch (IOException e) {
      LOG.error("Blog search failed for query: {}", sanitized, e);
      return List.of();
    }
  }

  public List<SearchResult> searchByType(final String query, final String type) {
    String sanitized = sanitizeQuery(query);
    if (sanitized.length() < MIN_QUERY_LENGTH) {
      return List.of();
    }

    try {
      SearchResponse<SiteSearchDocument> response = client.search(s -> s
              .index(ElasticsearchConfig.SITE_SEARCH_INDEX)
              .size(maxResultsPerGroup)
              .query(q -> q
                  .bool(b -> b
                      .must(m -> m
                          .multiMatch(mm -> typoTolerant(mm, sanitized, SITE_FIELDS)))
                      .filter(f -> f
                          .term(t -> t
                              .field("type")
                              .value(type)))))
              .sort(sort -> sort.score(sc -> sc
                  .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))),
          SiteSearchDocument.class);

      return response.hits().hits().stream()
          .map(Hit::source)
          .filter(doc -> doc != null)
          .map(doc -> new SearchResult(doc.name(),
              mediaVariantResolver.resolvePath(doc.image(), "thumbnail", "small", "medium"),
              doc.url()))
          .toList();
    } catch (IOException e) {
      LOG.error("Search by type '{}' failed for query: {}", type, sanitized, e);
      throw new SearchUnavailableException(
          "Search is temporarily unavailable. Please try again later.");
    }
  }

  /**
   * The one query shape every search here uses: best-fields across weighted fields, with a
   * term's spelling allowed to be slightly wrong.
   *
   * @param builder the multi-match builder being populated
   * @param query the already-sanitized query text
   * @param fields the boosted field list for the index being searched
   * @return the same builder, for use inside a lambda
   */
  private static MultiMatchQuery.Builder typoTolerant(
      final MultiMatchQuery.Builder builder,
      final String query,
      final List<String> fields) {
    return builder
        .query(query)
        .fields(fields)
        .type(TextQueryType.BestFields)
        .fuzziness(FUZZINESS)
        .prefixLength(FUZZY_PREFIX_LENGTH);
  }

  private List<SearchResult> toSearchResults(final List<SiteSearchDocument> documents) {
    return documents.stream()
        .limit(maxResultsPerGroup)
        .map(doc -> new SearchResult(
            doc.name(),
            mediaVariantResolver.resolvePath(doc.image(), "thumbnail", "small", "medium"),
            doc.url()))
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
