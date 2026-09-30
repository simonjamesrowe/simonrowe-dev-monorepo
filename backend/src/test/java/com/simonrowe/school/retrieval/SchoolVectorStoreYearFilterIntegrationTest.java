package com.simonrowe.school.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.simonrowe.school.SchoolProperties;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The year-group filter against a real Elasticsearch, through the real store configuration.
 *
 * <p>The unit tests stub the store, so they cannot say whether Spring AI's filter converter and
 * Elasticsearch agree about a <b>list</b>-valued metadata field — the converter turns
 * {@code in("yearGroups", "Year 3")} into a Lucene query string, and year groups are the first
 * list this index has ever filtered on. A mismatch there is silent: the scoped search returns
 * nothing, the broad one still answers, and the Year 3 note is simply outranked again.
 */
@Testcontainers
class SchoolVectorStoreYearFilterIntegrationTest {

  private static final int DIMENSIONS = 1536;

  @Container
  static ElasticsearchContainer elasticsearch =
      new ElasticsearchContainer("elasticsearch:9.4.5")
          .withEnv("xpack.security.enabled", "false");

  private static final String Y3 = "unhappy unusual disagree disobey";
  private static final String Y6 = "caution injection position solution";
  private static final String Y3_AND_4 = "accident actual address answer";
  private static final String WHOLE_SCHOOL = "spellings spellings spellings help";

  private static Rest5Client client;
  private static SchoolRetrievalService retrieval;

  @BeforeAll
  static void createStore() {
    client = Rest5Client.builder(URI.create("http://" + elasticsearch.getHttpHostAddress()))
        .build();
    final SchoolProperties properties = new SchoolProperties(
        true, null, List.of(), List.of(), null, null, null, 0, null, null, null, 0L, null,
        List.of());
    final StaticListableBeanFactory beans = new StaticListableBeanFactory(
        Map.of("client", client, "model", new BagOfWordsEmbeddingModel()));
    final SchoolVectorStore store = new SchoolVectorStoreConfig().schoolVectorStore(
        properties, beans.getBeanProvider(Rest5Client.class),
        beans.getBeanProvider(EmbeddingModel.class),
        "school-year-filter-" + UUID.randomUUID());

    // The whole-school chunk is by far the closest to the query and the tagged ones do not
    // contain its word at all, so only a working year filter can put a tagged chunk first. With
    // similarity doing the work instead, every test here passed with the filter key misspelt.
    store.add(List.of(
        chunk(Y3, List.of("Year 3")),
        chunk(Y6, List.of("Year 6")),
        chunk(Y3_AND_4, List.of("Year 3", "Year 4")),
        chunk(WHOLE_SCHOOL, List.of())));
    retrieval = new SchoolRetrievalService(store);
  }

  @AfterAll
  static void closeClient() throws Exception {
    client.close();
  }

  private static Document chunk(final String text, final List<String> years) {
    return new Document(text, Map.of("visibility", "PUBLIC", "yearGroups", years,
        "title", text, "sourceType", "PDF"));
  }

  private static List<String> textsFor(final List<String> years) throws InterruptedException {
    // Elasticsearch makes writes searchable on its refresh interval, one second by default.
    List<Document> hits = List.of();
    for (int attempt = 0; attempt < 20 && hits.isEmpty(); attempt++) {
      hits = retrieval.search("spellings", SchoolAudience.anonymous(), years);
      if (hits.isEmpty()) {
        Thread.sleep(250);
      }
    }
    return hits.stream().map(Document::getText).toList();
  }

  @Test
  @DisplayName("with no year selected the closest match comes first, as before")
  void noYearIsPlainSimilarity() throws InterruptedException {
    // The control: proves the whole-school chunk really does outrank the others on similarity,
    // so the assertions below can only pass through the year filter.
    assertThat(textsFor(List.of()).getFirst()).isEqualTo(WHOLE_SCHOOL);
  }

  @Test
  @DisplayName("Year 3 selected: Year 3's sources first, Year 6's gone, whole-school kept")
  void yearThreeSelected() throws InterruptedException {
    final List<String> texts = textsFor(List.of("Year 3"));

    assertThat(texts).hasSize(3);
    assertThat(texts.subList(0, 2)).containsExactlyInAnyOrder(Y3, Y3_AND_4);
    assertThat(texts.get(2)).isEqualTo(WHOLE_SCHOOL);
    assertThat(texts).doesNotContain(Y6);
  }

  @Test
  @DisplayName("the filter matches the whole value, so Year 4 does not match Year 3's sources")
  void filterMatchesWholeValues() throws InterruptedException {
    final List<String> texts = textsFor(List.of("Year 4"));

    assertThat(texts).containsExactly(Y3_AND_4, WHOLE_SCHOOL);
  }

  @Test
  @DisplayName("the year-tag filter on its own finds tagged chunks")
  void scopedFilterAloneFindsTaggedChunks() throws InterruptedException {
    // Guards against the merged result hiding a scoped search that matched nothing: with Year 6
    // selected the only Year 6 chunk must come first, which the broad search alone does not
    // promise when every chunk is about equally similar.
    final List<String> texts = textsFor(List.of("Year 6"));

    assertThat(texts).containsExactly(Y6, WHOLE_SCHOOL);
  }

  /**
   * A deterministic stand-in for the OpenAI model: each word hashes to one dimension.
   *
   * <p>Only the filter is under test, so every assertion above rests on the scoped results
   * coming first, never on similarity order.
   */
  private static final class BagOfWordsEmbeddingModel implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(final EmbeddingRequest request) {
      final List<String> inputs = request.getInstructions();
      final List<Embedding> embeddings = new java.util.ArrayList<>();
      for (int i = 0; i < inputs.size(); i++) {
        embeddings.add(new Embedding(vector(inputs.get(i)), i));
      }
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(final Document document) {
      return vector(document.getText());
    }

    @Override
    public int dimensions() {
      return DIMENSIONS;
    }

    private static float[] vector(final String text) {
      final float[] vector = new float[DIMENSIONS];
      for (String word : text.toLowerCase(java.util.Locale.ROOT).split("\\W+")) {
        if (!word.isEmpty()) {
          vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
        }
      }
      // A large shared component, so every chunk clears the store's 0.3 similarity threshold
      // and what comes back is decided by the filter alone.
      vector[0] += 3f;
      double norm = 0;
      for (float value : vector) {
        norm += value * value;
      }
      final float scale = (float) (1 / Math.sqrt(norm));
      for (int i = 0; i < vector.length; i++) {
        vector[i] *= scale;
      }
      return vector;
    }
  }
}
