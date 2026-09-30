package com.simonrowe.school.retrieval;

import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.model.YearGroups;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Prose retrieval for the school assistant, tier-filtered.
 *
 * <p>The only caller of {@link SchoolVectorStore#search}. Every search goes through here so the
 * visibility filter is applied in exactly one place — a second call site is the mechanism by which
 * a filter gets forgotten.
 */
@Service
public class SchoolRetrievalService {

  /** Chunk metadata key holding the year groups a document is about, empty for whole-school. */
  public static final String YEAR_GROUPS_KEY = "yearGroups";

  private static final double SIMILARITY_THRESHOLD = 0.3;
  private static final int TOP_K = 8;

  /**
   * How many unscoped results to read when a year group is selected.
   *
   * <p>Wider than {@link #TOP_K} because results tagged for other year groups are dropped from
   * it afterwards, and a Year 3 question about spellings has one near neighbour per year.
   */
  private static final int BROAD_TOP_K = TOP_K * 2;

  private final SchoolVectorStore vectorStore;

  public SchoolRetrievalService(final SchoolVectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  /**
   * Searches for prose relevant to a question, with no year group selected.
   *
   * @param query the user's question
   * @param audience which tiers this request may read
   * @return matching chunks, most similar first
   */
  public List<Document> search(final String query, final SchoolAudience audience) {
    return search(query, audience, List.of());
  }

  /**
   * Searches for prose relevant to a question, favouring the visitor's year groups.
   *
   * <p>Two searches when a year group is selected, merged. The first is restricted to chunks
   * tagged with one of the selected years, which is what guarantees they are found at all: a
   * pasted Year 3 spelling list scored 0.42 against "Year 3 spellings for this week" while four
   * other years' spelling sheets scored 0.56, so by similarity alone it came fifth and was cut.
   * The second is the ordinary search, minus anything tagged <b>only</b> for other years.
   *
   * <p>The year group is still <b>not</b> a hard filter on everything. Untagged content — the
   * lunch newsletter, the uniform policy, anything whole-school — stays in, because filtering
   * hard on it would make a whole-school newsletter invisible to a Year 4 parent. What is dropped
   * is only content the school itself published for a different year, which cannot be the answer
   * and was, in production, confidently offered as one.
   *
   * @param query the user's question
   * @param audience which tiers this request may read
   * @param yearGroups the visitor's selected year groups, empty for none
   * @return matching chunks: the selected years' first, then the rest, most similar first within
   *     each
   */
  public List<Document> search(
      final String query, final SchoolAudience audience, final List<String> yearGroups) {
    final List<String> selected = YearGroups.sanitise(yearGroups);
    final FilterExpressionBuilder builder = new FilterExpressionBuilder();
    final FilterExpressionBuilder.Op tier = tierFilter(builder, audience);

    if (selected.isEmpty()) {
      return vectorStore.search(request(query, TOP_K, tier.build()));
    }

    final List<Document> scoped = vectorStore.search(request(query, TOP_K,
        builder.and(tier, builder.in(YEAR_GROUPS_KEY, selected.toArray())).build()));
    final List<Document> broad = vectorStore.search(request(query, BROAD_TOP_K, tier.build()));

    final Set<String> included = new LinkedHashSet<>();
    final List<Document> merged = new ArrayList<>();
    for (Document document : scoped) {
      // Re-checked here rather than trusted to the filter: the filter is a Lucene query string
      // matched against a text field, and a result that reaches the model labelled for a year it
      // is not about is the exact failure this method exists to stop.
      if (merged.size() < TOP_K && isFor(document, selected) && included.add(document.getId())) {
        merged.add(document);
      }
    }
    for (Document document : broad) {
      if (merged.size() < TOP_K && !isOnlyForOtherYears(document, selected)
          && included.add(document.getId())) {
        merged.add(document);
      }
    }
    return merged;
  }

  private static FilterExpressionBuilder.Op tierFilter(
      final FilterExpressionBuilder builder, final SchoolAudience audience) {
    final List<String> allowed = audience.visibilities().stream().map(Visibility::name).toList();
    return builder.in("visibility", allowed.toArray());
  }

  private static SearchRequest request(
      final String query, final int topK, final Filter.Expression filter) {
    return SearchRequest.builder()
        .query(query)
        .similarityThreshold(SIMILARITY_THRESHOLD)
        .topK(topK)
        .filterExpression(filter)
        .build();
  }

  /**
   * The year groups a chunk is tagged with, empty when it has none.
   *
   * <p>Empty for every chunk written before the tag existed, which is what makes those chunks
   * count as whole-school rather than vanish.
   *
   * @param document a retrieved chunk
   * @return its year groups
   */
  public static List<String> yearGroupsOf(final Document document) {
    final Object value = document.getMetadata().get(YEAR_GROUPS_KEY);
    if (value instanceof Collection<?> values) {
      return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    if (value instanceof String single && !single.isBlank()) {
      return List.of(single);
    }
    return List.of();
  }

  private static boolean isFor(final Document document, final List<String> selected) {
    return yearGroupsOf(document).stream().anyMatch(selected::contains);
  }

  private static boolean isOnlyForOtherYears(
      final Document document, final List<String> selected) {
    final List<String> years = yearGroupsOf(document);
    return !years.isEmpty() && years.stream().noneMatch(selected::contains);
  }
}
