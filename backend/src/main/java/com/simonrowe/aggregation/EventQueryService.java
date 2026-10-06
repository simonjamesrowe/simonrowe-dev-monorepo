package com.simonrowe.aggregation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/**
 * Reads the admin console's events listing with its filters applied.
 *
 * <p>Uses the same term splitting, quoting and caps as {@link ArticleQueryService}, so the
 * two tabs of the admin page behave identically for the same input; only the fields
 * searched and the sortable fields differ.
 */
@Service
public class EventQueryService {

  /** Fields the admin events listing may sort on; see {@link AdminSort}. */
  public static final Set<String> ADMIN_SORT_FIELDS =
      Set.of("eventDate", "title", "sourceName");

  /** The admin events listing's default order: latest event date first. */
  public static final String ADMIN_DEFAULT_SORT = "eventDate";

  /**
   * What a term is matched against: what the admin table and an event card show, plus
   * where it happens. Not {@code description}, the scraped page body, for the same reason
   * articles leave out {@code fullContent}.
   */
  private static final List<String> SEARCHED_FIELDS =
      List.of("title", "summary", "venue", "location", "sourceName");

  private final MongoTemplate mongoTemplate;

  public EventQueryService(final MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * One page of events for the admin console.
   *
   * @param listing the filters and sort
   * @param pageable the page to read; its own sort, if any, is ignored
   * @return the page, empty when nothing matches
   */
  public Page<AggregatedEvent> findForAdmin(final AdminListing listing, final Pageable pageable) {
    List<Criteria> clauses = new ArrayList<>();
    listing.visibility().criteria().ifPresent(clauses::add);
    clauses.addAll(ArticleQueryService.filterClauses(
        listing.sources(), listing.query(), SEARCHED_FIELDS));
    return ArticleQueryService.adminPage(mongoTemplate,
        ArticleQueryService.combine(clauses), listing.sort(), pageable, AggregatedEvent.class);
  }
}
