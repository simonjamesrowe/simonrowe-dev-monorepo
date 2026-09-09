package com.simonrowe.school.retrieval;

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.simonrowe.school.SchoolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Term Time vector index.
 *
 * <p><b>Always registers a bean</b>, and decides inside the method whether it is a real store or
 * {@link SchoolVectorStore#disabled()}. Deliberately not a {@code @ConditionalOnProperty} on the
 * bean or the class: the services that depend on this are plain {@code @Service} beans, so a
 * missing store makes the whole application context fail to start rather than disabling one
 * feature. That is not hypothetical — wiring it conditionally broke every unrelated controller
 * test in the suite.
 *
 * <p>The bean type is {@link SchoolVectorStore}, not {@code VectorStore} — see that class for why
 * publishing a second {@code VectorStore} bean would silently remove the main site's store.
 */
@Configuration
public class SchoolVectorStoreConfig {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolVectorStoreConfig.class);

  /** Matches text-embedding-3-small, the model the rest of this application already uses. */
  private static final int DIMENSIONS = 1536;

  /**
   * Creates the school vector store, real or disabled.
   *
   * @param properties the feature configuration
   * @param rest5Client the Elasticsearch low-level client, absent in tests that mock the search
   *     stack — hence an {@link ObjectProvider} rather than a hard dependency
   * @param embeddingModel the shared embedding model, likewise
   * @param indexName the school index name
   * @return the wrapped store
   */
  @Bean
  public SchoolVectorStore schoolVectorStore(
      final SchoolProperties properties,
      final ObjectProvider<Rest5Client> rest5Client,
      final ObjectProvider<EmbeddingModel> embeddingModel,
      @Value("${school.embedding-index-name:school-embeddings}") final String indexName) {

    if (!properties.enabled()) {
      return SchoolVectorStore.disabled();
    }

    final Rest5Client client = rest5Client.getIfAvailable();
    final EmbeddingModel model = embeddingModel.getIfAvailable();
    if (client == null || model == null) {
      LOG.warn("school.enabled is true but Elasticsearch or the embedding model is unavailable; "
          + "Term Time will find nothing");
      return SchoolVectorStore.disabled();
    }

    final ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
    options.setIndexName(indexName);
    options.setDimensions(DIMENSIONS);
    options.setSimilarity(SimilarityFunction.cosine);

    final ElasticsearchVectorStore store = ElasticsearchVectorStore
        .builder(client, model)
        .options(options)
        .initializeSchema(true)
        .build();

    // ElasticsearchVectorStore implements InitializingBean, and initializeSchema(true) only
    // takes effect from afterPropertiesSet(). Because this instance is wrapped rather than
    // published as a bean, Spring calls that lifecycle method on the SchoolVectorStore wrapper
    // and never on the store inside it — so the index would never be created, the first search
    // would return nothing, and no error would be raised anywhere. Call it by hand.
    try {
      store.afterPropertiesSet();
    } catch (Exception ex) {
      throw new IllegalStateException("Could not initialise the " + indexName + " index", ex);
    }

    LOG.info("Term Time vector store ready on index {}", indexName);
    return new SchoolVectorStore(store, indexName);
  }
}
