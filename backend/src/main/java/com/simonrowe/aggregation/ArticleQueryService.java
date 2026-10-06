package com.simonrowe.aggregation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Reads the visible news feed with the News &amp; Events page's own filters applied: any
 * number of sources at once, and a free-text term. Also backs the admin console's listing,
 * which applies the same filters to hidden articles too, in a caller-chosen order.
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

  /** Fields the admin listing may sort on; see {@link AdminSort}. */
  public static final Set<String> ADMIN_SORT_FIELDS =
      Set.of("publishedDate", "fetchedAt", "title", "sourceName");

  /** The admin listing's default order: newest first, as the public feed is. */
  public static final String ADMIN_DEFAULT_SORT = "publishedDate";

  /**
   * What a term is matched against. Not {@code fullContent}: the visitor is filtering a
   * list of cards, and matching text they cannot see on a card produces results that look
   * like a bug.
   */
  private static final List<String> SEARCHED_FIELDS =
      List.of("title", "summary", "author", "sourceName");

  private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "publishedDate");

  private static final Collation ADMIN_COLLATION =
      Collation.of(Locale.ENGLISH).strength(Collation.ComparisonLevel.secondary());

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

  /**
   * One page of articles for the admin console, hidden ones included unless the listing
   * says otherwise, in the order the listing asks for.
   *
   * <p>Shares the source and free-text clauses with {@link #find} so the admin box matches
   * exactly what the public box matches; only the visibility clause and the sort differ.
   *
   * @param listing the filters and sort
   * @param pageable the page to read; its own sort, if any, is ignored in favour of the
   *     listing's
   * @return the page, empty when nothing matches
   */
  public Page<AggregatedArticle> findForAdmin(
      final AdminListing listing, final Pageable pageable) {
    List<Criteria> clauses = new ArrayList<>();
    listing.visibility().criteria().ifPresent(clauses::add);
    clauses.addAll(filterClauses(listing.sources(), listing.query(), SEARCHED_FIELDS));
    return adminPage(mongoTemplate, combine(clauses), listing.sort(), pageable,
        AggregatedArticle.class);
  }

  /**
   * Every source across all articles, hidden ones included, with its article count,
   * busiest first.
   *
   * <p>The admin counterpart of {@code GET /api/news/sources}, which counts visible
   * articles only. A source whose every article has been hidden still needs to be findable
   * here, or there is no way to filter to it and bring them back.
   *
   * @return the source summaries, empty when there are no articles
   */
  public List<SourceSummary> allSources() {
    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.group("sourceName").count().as("count"),
        Aggregation.project("count").and("_id").as("name"),
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "count")
            .and(Sort.by(Sort.Direction.ASC, "name"))));
    return mongoTemplate
        .aggregate(aggregation, AggregatedArticle.class, SourceSummary.class)
        .getMappedResults()
        .stream()
        .filter(summary -> summary.name() != null)
        .toList();
  }

  private Criteria criteria(final List<String> sources, final String query) {
    List<Criteria> clauses = new ArrayList<>();
    clauses.add(Criteria.where("visible").is(true));
    clauses.addAll(filterClauses(sources, query, SEARCHED_FIELDS));
    return combine(clauses);
  }

  /**
   * The source and free-text clauses shared by every listing.
   *
   * @param sources the source names to include; null or empty adds no clause
   * @param query free text; null or blank adds no clause
   * @param searchedFields the fields a term may appear in
   * @return the clauses, empty when there is nothing to filter on
   */
  static List<Criteria> filterClauses(
      final List<String> sources, final String query, final List<String> searchedFields) {
    List<Criteria> clauses = new ArrayList<>();

    List<String> named = namedSources(sources);
    if (!named.isEmpty()) {
      clauses.add(Criteria.where("sourceName").in(named));
    }

    // Every term must appear somewhere, but each may appear in a different field — so
    // "spring boot" matches a Spring Blog article titled "Boot 4.1.1 available now".
    for (String term : terms(query)) {
      Pattern pattern = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE);
      Criteria[] anyField = searchedFields.stream()
          .map(field -> Criteria.where(field).regex(pattern))
          .toArray(Criteria[]::new);
      clauses.add(new Criteria().orOperator(anyField));
    }
    return clauses;
  }

  /**
   * ANDs the clauses together; no clauses at all means match everything.
   *
   * @param clauses the clauses
   * @return the combined criteria
   */
  static Criteria combine(final List<Criteria> clauses) {
    if (clauses.isEmpty()) {
      return new Criteria();
    }
    return clauses.size() == 1
        ? clauses.get(0)
        : new Criteria().andOperator(clauses.toArray(new Criteria[0]));
  }

  /**
   * Reads one page of an admin listing.
   *
   * <p>Sorted under a case-insensitive English collation, so a title sort puts "apple"
   * beside "Apple" rather than after every capitalised title. The collation also makes the
   * source filter's equality match case-insensitive, which is harmless here: source names
   * differing only by case are the same publisher. Free-text matching is unaffected, since
   * Mongo's {@code $regex} ignores collation and each pattern is already case-insensitive.
   *
   * @param mongoTemplate the template to read with
   * @param criteria the filter
   * @param sort the order
   * @param pageable the page
   * @param type the document type
   * @param <T> the document type
   * @return the page
   */
  static <T> Page<T> adminPage(
      final MongoTemplate mongoTemplate,
      final Criteria criteria,
      final Sort sort,
      final Pageable pageable,
      final Class<T> type) {
    long total = mongoTemplate.count(
        Query.query(criteria).collation(ADMIN_COLLATION), type);
    Query query = Query.query(criteria)
        .collation(ADMIN_COLLATION)
        .with(sort)
        .skip(pageable.getOffset())
        .limit(pageable.getPageSize());
    return new PageImpl<>(mongoTemplate.find(query, type), pageable, total);
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
  public static List<String> terms(final String query) {
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
