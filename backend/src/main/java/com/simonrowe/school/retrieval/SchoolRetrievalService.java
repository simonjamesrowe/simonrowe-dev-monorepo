package com.simonrowe.school.retrieval;

import com.simonrowe.school.model.Visibility;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
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

  private static final double SIMILARITY_THRESHOLD = 0.3;
  private static final int TOP_K = 8;

  private final SchoolVectorStore vectorStore;

  public SchoolRetrievalService(final SchoolVectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  /**
   * Searches for prose relevant to a question.
   *
   * <p>The year group is deliberately <b>not</b> a filter. Year attribution on prose is inferred
   * by a model, not published structurally the way calendar sub-calendars are, so filtering hard
   * on it makes a whole-school lunch newsletter invisible to a Year 4 parent. It is passed to the
   * model as context instead, where being wrong costs relevance rather than an answer.
   *
   * @param query the user's question
   * @param audience which tiers this request may read
   * @return matching chunks, most similar first
   */
  public List<Document> search(final String query, final SchoolAudience audience) {
    final FilterExpressionBuilder builder = new FilterExpressionBuilder();
    final List<String> allowed = audience.visibilities().stream().map(Visibility::name).toList();

    final SearchRequest request = SearchRequest.builder()
        .query(query)
        .similarityThreshold(SIMILARITY_THRESHOLD)
        .topK(TOP_K)
        .filterExpression(builder.in("visibility", allowed.toArray()).build())
        .build();

    return vectorStore.search(request);
  }
}
