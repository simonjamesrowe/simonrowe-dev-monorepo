package com.simonrowe.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.simonrowe.admin.AdminCodeExampleRepository;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.search.elasticsearch.ElasticsearchJsonpMapperConfig;
import com.simonrowe.skills.SkillGroupRepository;
import com.simonrowe.skills.SkillRepository;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Skipping unchanged content, against a real Elasticsearch through the real vector store.
 *
 * <p>The skip decision is two {@code _count} queries on {@code metadata.*.keyword}, fields that
 * exist only because Elasticsearch maps the store's metadata dynamically. A stubbed client
 * cannot say whether those fields exist or match; if they did not, every check would answer
 * "changed" and the sync would quietly keep re-embedding everything, which is the bill this
 * class exists to stop. The model here counts its calls, so each test reads the bill directly.
 */
@Testcontainers
class EmbeddingServiceUnchangedContentIntegrationTest {

  private static final int DIMENSIONS = 8;

  @Container
  static ElasticsearchContainer elasticsearch =
      new ElasticsearchContainer("elasticsearch:9.4.5")
          .withEnv("xpack.security.enabled", "false");

  private static Rest5Client lowLevel;
  private static ElasticsearchClient client;

  private final CountingEmbeddingModel model = new CountingEmbeddingModel();
  private final List<AggregatedArticle> articles = new ArrayList<>();
  private String index;
  private EmbeddingService service;

  @BeforeAll
  static void createClient() {
    lowLevel = Rest5Client.builder(URI.create("http://" + elasticsearch.getHttpHostAddress()))
        .build();
    client = new ElasticsearchClient(new Rest5ClientTransport(
        lowLevel, new ElasticsearchJsonpMapperConfig().jacksonJsonpMapper()));
  }

  @AfterAll
  static void closeClient() throws Exception {
    lowLevel.close();
  }

  @BeforeEach
  void createService() throws Exception {
    index = "content-embeddings-" + UUID.randomUUID();
    final ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
    options.setIndexName(index);
    options.setDimensions(DIMENSIONS);
    options.setSimilarity(SimilarityFunction.cosine);
    final ElasticsearchVectorStore store = ElasticsearchVectorStore.builder(lowLevel, model)
        .options(options)
        .initializeSchema(true)
        .build();
    store.afterPropertiesSet();

    final AggregatedArticleRepository articleRepository = mock(AggregatedArticleRepository.class);
    when(articleRepository.findByVisibleTrueOrderByPublishedDateDesc()).thenReturn(articles);
    service = new EmbeddingService(
        store,
        new EmbeddingConfig().tokenTextSplitter(),
        mock(BlogRepository.class),
        mock(JobRepository.class),
        mock(SkillRepository.class),
        mock(SkillGroupRepository.class),
        mock(AdminCodeExampleRepository.class),
        articleRepository,
        mock(AggregatedEventRepository.class),
        client,
        index);
  }

  private static AggregatedArticle article(final String id, final String body) {
    return new AggregatedArticle(id, "Title " + id, "Source", "https://source.example",
        "https://source.example/" + id, "A summary of " + id, body, "Author",
        Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), true,
        null);
  }

  private void refresh() throws Exception {
    lowLevel.performRequest(new Request("POST", "/" + index + "/_refresh"));
  }

  private long chunksFor(final String sourceId) throws Exception {
    refresh();
    return client.count(c -> c.index(index).query(q -> q.term(
        t -> t.field("metadata.sourceId.keyword").value(sourceId)))).count();
  }

  private void sync() throws Exception {
    service.fullVectorSync();
    refresh();
  }

  @Test
  @DisplayName("a second sync over unchanged content makes no embedding call")
  void unchangedContentIsNotReembedded() throws Exception {
    articles.add(article("a1", "Kafka consumer groups and rebalancing."));
    articles.add(article("a2", "Spring Boot virtual threads in practice."));

    sync();
    final int afterFirst = model.calls.get();
    sync();

    // The control: the first sync really did embed, so zero on the second is the skip working.
    assertThat(afterFirst).isPositive();
    assertThat(model.calls.get()).isEqualTo(afterFirst);
    assertThat(chunksFor("news_a1")).isEqualTo(1);
    assertThat(chunksFor("news_a2")).isEqualTo(1);
  }

  @Test
  @DisplayName("an edited article is re-embedded, and its old chunks are replaced not kept")
  void changedContentIsReembedded() throws Exception {
    articles.add(article("a1", "The original body."));
    sync();
    final int afterFirst = model.calls.get();

    articles.set(0, article("a1", "A corrected body, with a new paragraph."));
    sync();

    assertThat(model.calls.get()).isGreaterThan(afterFirst);
    assertThat(chunksFor("news_a1")).isEqualTo(1);
    assertThat(service.indexedUnchanged("news_a1",
        EmbeddingService.fingerprint("anything else", java.util.Map.of()))).isFalse();
  }

  @Test
  @DisplayName("content missing from the index is embedded, even when nothing in Mongo changed")
  void lostVectorsAreRestored() throws Exception {
    articles.add(article("a1", "Body that will be lost from the index."));
    sync();
    service.removeContent("news_a1");
    refresh();
    assertThat(chunksFor("news_a1")).isZero();
    final int beforeResync = model.calls.get();

    sync();

    assertThat(model.calls.get()).isGreaterThan(beforeResync);
    assertThat(chunksFor("news_a1")).isEqualTo(1);
  }

  @Test
  @DisplayName("the Data Ops re-embed still embeds everything, unchanged or not")
  void forcedReembedIgnoresTheFingerprint() throws Exception {
    articles.add(article("a1", "Unchanged body."));
    sync();
    final int afterFirst = model.calls.get();

    assertThat(service.embedAllArticles()).isEqualTo(1);
    refresh();

    assertThat(model.calls.get()).isGreaterThan(afterFirst);
    assertThat(chunksFor("news_a1")).isEqualTo(1);
  }

  @Test
  @DisplayName("a metadata-only change, such as a new title, is re-embedded")
  void metadataChangeIsReembedded() throws Exception {
    final AggregatedArticle original = article("a1", "Same body.");
    articles.add(original);
    sync();
    final int afterFirst = model.calls.get();

    articles.set(0, new AggregatedArticle(original.id(), "A better title",
        original.sourceName(), original.sourceUrl(), original.originalUrl(), original.summary(),
        original.fullContent(), original.author(), original.publishedDate(),
        original.fetchedAt(), true, null));
    sync();

    assertThat(model.calls.get()).isGreaterThan(afterFirst);
  }

  /** A deterministic model that counts how many times it was asked for vectors. */
  private static final class CountingEmbeddingModel implements EmbeddingModel {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public EmbeddingResponse call(final EmbeddingRequest request) {
      calls.incrementAndGet();
      final List<Embedding> embeddings = new ArrayList<>();
      for (int i = 0; i < request.getInstructions().size(); i++) {
        embeddings.add(new Embedding(vector(request.getInstructions().get(i)), i));
      }
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(final Document document) {
      calls.incrementAndGet();
      return vector(document.getText());
    }

    @Override
    public int dimensions() {
      return DIMENSIONS;
    }

    private static float[] vector(final String text) {
      final float[] vector = new float[DIMENSIONS];
      vector[Math.floorMod(text.hashCode(), DIMENSIONS)] = 1f;
      return vector;
    }
  }
}
