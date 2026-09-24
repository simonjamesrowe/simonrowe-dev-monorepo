package com.simonrowe.aggregation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Reads the visible news feed with the News &amp; Events page's own filters applied: any
 * number of sources at once, and a free-text term.
 *
 * <p>Deliberately Mongo rather than Elasticsearch, even though the site-wide search box
 * next to it is Elasticsearch. What this backs is a filter over a date-ordered, paged
 * listing — the visitor still expects the newest article first, and "Load more" still has
 * to page through the whole matching set. Relevance ranking is the wrong shape for that,
 * and routing it through the search index would mean reconciling two sources of truth for
 * one list. The cost is that the page's box is literal where the site box tolerates a typo.
 *
 * <p>A substring match cannot use an index, so this is a collection scan over the visible
 * articles. That is affordable at the low thousands this collection holds and would not be
 * at a hundred times that; the cutover point is a text index, not a bigger regex.
 */
@Service
public class ArticleQueryService {

  /**
   * Longer than any query anyone types into a filter box, and short enough that the scan
   * stays bounded however many words are crammed in.
   */
  static final int MAX_QUERY_LENGTH = 100;

  /** Each extra word is another pass over every candidate document. */
  static final int MAX_TERMS = 6;

  /**
   * What a term is matched against. Not {@code fullContent}: the visitor is filtering a
   * list of cards, and matching text they cannot see on a card produces results that look
   * like a bug.
   */
  private static final List<String> SEARCHED_FIELDS =
      List.of("title", "summary", "author", "sourceName");

  private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "publishedDate");

  private final MongoTemplate mongoTemplate;

  public ArticleQueryService(final MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * One page of visible articles, newest first.
   *
   * @param sources the source names to include; null or empty means every source
   * @param query free text; null or blank means no text filter
   * @param pageable the page to read
   * @return the page, empty when nothing matches
   */
  public Page<AggregatedArticle> find(
      final List<String> sources, final String query, final Pageable pageable) {
    Criteria criteria = criteria(sources, query);
    long total = mongoTemplate.count(Query.query(criteria), AggregatedArticle.class);
    List<AggregatedArticle> content = mongoTemplate.find(
        Query.query(criteria).with(NEWEST_FIRST).with(pageable), AggregatedArticle.class);
    return new PageImpl<>(content, pageable, total);
  }

  private Criteria criteria(final List<String> sources, final String query) {
    List<Criteria> clauses = new ArrayList<>();
    clauses.add(Criteria.where("visible").is(true));

    List<String> named = namedSources(sources);
    if (!named.isEmpty()) {
      clauses.add(Criteria.where("sourceName").in(named));
    }

    // Every term must appear somewhere, but each may appear in a different field — so
    // "spring boot" matches a Spring Blog article titled "Boot 4.1.1 available now".
    for (String term : terms(query)) {
      Pattern pattern = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE);
      Criteria[] anyField = SEARCHED_FIELDS.stream()
          .map(field -> Criteria.where(field).regex(pattern))
          .toArray(Criteria[]::new);
      clauses.add(new Criteria().orOperator(anyField));
    }

    return clauses.size() == 1
        ? clauses.get(0)
        : new Criteria().andOperator(clauses.toArray(new Criteria[0]));
  }

  /**
   * The requested sources, cleaned up. A blank entry is dropped rather than treated as a
   * source nothing matches, so {@code ?source=} behaves like no filter at all.
   */
  private static List<String> namedSources(final List<String> sources) {
    if (sources == null) {
      return List.of();
    }
    return sources.stream()
        .filter(name -> name != null && !name.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }

  /**
   * Splits the query into terms.
   *
   * <p>Each term is used as a quoted literal, so no input can introduce a quantifier and
   * there is nothing for a regex engine to backtrack over. The length and count caps bound
   * the work done per document rather than guarding against a crafted pattern.
   *
   * @param query the raw query text
   * @return the terms, empty when there is nothing to match on
   */
  static List<String> terms(final String query) {
    if (query == null) {
      return List.of();
    }
    String trimmed = query.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    if (trimmed.length() > MAX_QUERY_LENGTH) {
      trimmed = trimmed.substring(0, MAX_QUERY_LENGTH);
    }
    return Arrays.stream(trimmed.split("\\s++"))
        .filter(term -> !term.isBlank())
        .limit(MAX_TERMS)
        .toList();
  }
}
