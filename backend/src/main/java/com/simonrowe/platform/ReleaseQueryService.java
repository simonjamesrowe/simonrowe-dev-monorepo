package com.simonrowe.platform;

import com.simonrowe.aggregation.ArticleQueryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Pages and searches the changelog.
 *
 * <p>The whole stored history is reachable, a page at a time. The page used to fetch the newest
 * 20 releases and filter them in the browser, so anything older was unreachable and a search
 * could only ever search 20 entries.
 *
 * <p>Search follows the news page ({@link ArticleQueryService}): every term must appear, each
 * may appear in a different field, and each term is matched as a quoted literal, so no input
 * can introduce a quantifier for the regex engine to backtrack over. A substring match is a
 * collection scan, which is affordable at one document per merge to {@code main}.
 *
 * <p><b>Nothing here may call an LLM</b>, for the reason given on {@link PlatformStatusService}.
 */
@Service
public class ReleaseQueryService {

  /** The page size when the request names none. */
  static final int DEFAULT_PAGE_SIZE = 10;

  /** Ceiling on the page size, so a crafted request cannot dump the collection in one go. */
  static final int MAX_PAGE_SIZE = 50;

  /**
   * What a term is matched against: everything an entry shows. {@code _id} so a pasted SHA, or
   * the start of one, finds its release.
   */
  private static final List<String> SEARCHED_FIELDS = List.of("subject", "summary", "_id");

  private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "commitTime");

  private final MongoTemplate mongoTemplate;
  private final RunningVersion runningVersion;

  /**
   * Creates the service.
   *
   * @param mongoTemplate the template the releases are read through
   * @param runningVersion this backend's version, which decides the {@code running} flag
   */
  public ReleaseQueryService(
      final MongoTemplate mongoTemplate, final RunningVersion runningVersion) {
    this.mongoTemplate = mongoTemplate;
    this.runningVersion = runningVersion;
  }

  /**
   * One page of releases, newest first.
   *
   * @param page the zero-based page; negative is treated as the first page
   * @param size the page size; clamped to between 1 and {@link #MAX_PAGE_SIZE}
   * @param type a conventional-commit type to keep, or null/blank for every type
   * @param query free text, or null/blank for no text filter
   * @return the page; empty items when nothing matches, never null
   */
  public ReleasePage find(
      final int page, final int size, final String type, final String query) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
    Criteria criteria = criteria(type, query);

    long matching = mongoTemplate.count(Query.query(criteria), PlatformRelease.class);
    String runningSha = runningVersion.commit();
    List<ReleaseResponse> items = mongoTemplate.find(
            Query.query(criteria)
                .with(NEWEST_FIRST)
                .with(PageRequest.of(safePage, safeSize)),
            PlatformRelease.class)
        .stream()
        .map(release -> ReleaseResponse.from(release, runningSha))
        .toList();

    int totalPages = (int) ((matching + safeSize - 1) / safeSize);
    long totalReleases = mongoTemplate.count(new Query(), PlatformRelease.class);
    return new ReleasePage(
        items, safePage, safeSize, matching, totalPages, totalReleases, typeCounts());
  }

  private static Criteria criteria(final String type, final String query) {
    List<Criteria> clauses = new ArrayList<>();
    if (type != null && !type.isBlank()) {
      clauses.add(Criteria.where("type").is(type.trim()));
    }
    for (String term : ArticleQueryService.terms(query)) {
      Pattern pattern = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE);
      Criteria[] anyField = SEARCHED_FIELDS.stream()
          .map(field -> Criteria.where(field).regex(pattern))
          .toArray(Criteria[]::new);
      clauses.add(new Criteria().orOperator(anyField));
    }
    return switch (clauses.size()) {
      case 0 -> new Criteria();
      case 1 -> clauses.get(0);
      default -> new Criteria().andOperator(clauses.toArray(new Criteria[0]));
    };
  }

  /**
   * Releases per type over the whole collection, most common first.
   *
   * @return type to count, ordered by count descending then type name
   */
  private Map<String, Long> typeCounts() {
    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.group("type").count().as("count"),
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "count").and(Sort.by("_id"))));
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Document row : mongoTemplate.aggregate(
        aggregation, PlatformRelease.class, Document.class).getMappedResults()) {
      Object key = row.get("_id");
      Number count = row.get("count", Number.class);
      if (key != null && count != null) {
        counts.put(key.toString(), count.longValue());
      }
    }
    return counts;
  }
}
