package com.simonrowe.school.retrieval;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Term Time vector index, held behind a wrapper rather than exposed as a second
 * {@link VectorStore} bean.
 *
 * <p><b>The wrapper is the whole point and must not be "simplified" away.</b> Spring AI's
 * Elasticsearch vector store autoconfiguration is {@code @ConditionalOnMissingBean(VectorStore)},
 * so publishing any second bean that <i>is</i> a {@code VectorStore} — including a qualified or
 * non-primary one, since the condition matches on type — makes the autoconfiguration back off and
 * the main site's store disappear. The portfolio chat would then fail to start, or worse, start
 * with the school's store injected into it. Composing rather than extending keeps exactly one
 * {@code VectorStore} bean in the context.
 *
 * <p>It is also a second physical index rather than a metadata filter on the main
 * {@code content-embeddings} index. Sharing one index would mean every portfolio chat turn
 * searches a corpus containing school mail, separated only by a filter clause that a future
 * change can forget to apply.
 */
public class SchoolVectorStore {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolVectorStore.class);

  private final VectorStore delegate;
  private final String indexName;

  /**
   * Creates the wrapper.
   *
   * @param delegate the underlying store, never published as a bean
   * @param indexName the index this store writes to, for logging and backup coordination
   */
  public SchoolVectorStore(final VectorStore delegate, final String indexName) {
    this.delegate = delegate;
    this.indexName = indexName;
  }

  /**
   * An instance that stores nothing and finds nothing, used when the feature is switched off or
   * Elasticsearch is not present.
   *
   * <p>A null object rather than a conditional bean. The services above this one are ordinary
   * {@code @Service} beans, so gating only the store leaves them unsatisfiable and the whole
   * application context fails to start — which is what happened the first time this was wired,
   * taking every unrelated controller test down with it. A disabled instance keeps the graph
   * intact so the controller can answer "not switched on" instead of the context refusing to load.
   *
   * @return a store that no-ops on write and returns nothing on read
   */
  public static SchoolVectorStore disabled() {
    return new SchoolVectorStore(null, "disabled");
  }

  /**
   * Whether this store is backed by a real index.
   *
   * @return false when the feature is off
   */
  public boolean isEnabled() {
    return delegate != null;
  }

  /**
   * Adds or replaces documents in the school index.
   *
   * @param documents the chunks to index
   */
  public void add(final List<Document> documents) {
    if (delegate == null || documents.isEmpty()) {
      return;
    }
    delegate.add(documents);
  }

  /**
   * Deletes chunks by id.
   *
   * @param ids the chunk ids to remove
   */
  public void delete(final List<String> ids) {
    if (delegate == null || ids.isEmpty()) {
      return;
    }
    delegate.delete(ids);
  }

  /**
   * Removes every chunk belonging to a document.
   *
   * <p>Must be called before re-embedding. Spring AI mints a fresh random id for each
   * {@code Document} it stores, so {@code add} on the same source content <b>appends a second
   * complete set of chunks</b> rather than replacing the first. Left unfixed that is not merely
   * untidy: revoking a document's public status adds RESTRICTED chunks while the previous PUBLIC
   * ones stay in the index and stay retrievable by anonymous callers — the revoke appears to
   * work and changes nothing that matters.
   *
   * @param documentId the owning document's id
   */
  public void deleteForDocument(final String documentId) {
    if (delegate == null || documentId == null || documentId.isBlank()) {
      return;
    }
    try {
      delegate.delete(new FilterExpressionBuilder().eq("documentId", documentId).build());
    } catch (RuntimeException e) {
      // A delete that fails must not stop the add that follows: a document with no chunks at
      // all is worse than one with a stale duplicate.
      LOG.warn("Could not clear existing chunks for {}: {}", documentId, e.getMessage());
    }
  }

  /**
   * Runs a similarity search.
   *
   * <p>Callers must supply a filter expression restricting {@code visibility}. This class
   * deliberately does not add one itself: a default applied here would be invisible at the call
   * site, and a reader checking whether the public path is safe would have to know to look in a
   * different file. {@code SchoolRetrievalService} owns that decision and is the only caller.
   *
   * @param request the search, including its tier filter
   * @return matching chunks, or an empty list
   */
  public List<Document> search(final SearchRequest request) {
    if (delegate == null) {
      return List.of();
    }
    final List<Document> results = delegate.similaritySearch(request);
    return results == null ? List.of() : results;
  }

  /**
   * The index this store is backed by.
   *
   * @return the Elasticsearch index name
   */
  public String indexName() {
    return indexName;
  }
}
