package com.simonrowe.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import com.simonrowe.shortlink.ShortLinkProperties;
import com.simonrowe.summary.ArticleSummaryService;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The hearted-only filter against a real Elasticsearch, through the real vector store.
 *
 * <p>The filter is a Lucene query string on {@code metadata.sourceId.keyword}, a field that
 * exists only because Elasticsearch maps the store's metadata dynamically. A mocked store cannot
 * say whether that field matches, and a filter that matched nothing would make the tool answer
 * "nothing relevant" for every question. So the closest chunk to the query here belongs to an
 * article that is NOT hearted: the test passes only if the filter really excludes it.
 */
@Testcontainers
class FavouriteArticleMcpToolsIntegrationTest {

  private static final int DIMENSIONS = 16;
  private static final String HEARTED = "6ad212093f737ee1c34c602f";
  private static final String NOT_HEARTED = "6ae092d0c073f1e009cda479";
  private static final String QUERY = "auto merge rules for agent pull requests";

  @Container
  static ElasticsearchContainer elasticsearch =
      new ElasticsearchContainer("elasticsearch:9.4.5")
          .withEnv("xpack.security.enabled", "false");

  private static Rest5Client lowLevel;

  private ElasticsearchVectorStore store;
  private FavouriteArticleMcpTools tools;

  @BeforeAll
  static void createClient() {
    lowLevel = Rest5Client.builder(URI.create("http://" + elasticsearch.getHttpHostAddress()))
        .build();
  }

  @AfterAll
  static void closeClient() throws Exception {
    lowLevel.close();
  }

  @BeforeEach
  void createStore() throws Exception {
    final String index = "content-embeddings-" + UUID.randomUUID();
    final ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
    options.setIndexName(index);
    options.setDimensions(DIMENSIONS);
    options.setSimilarity(SimilarityFunction.cosine);
    store = ElasticsearchVectorStore.builder(lowLevel, new BagOfWordsEmbeddingModel())
        .options(options)
        .initializeSchema(true)
        .build();
    store.afterPropertiesSet();

    // Metadata exactly as EmbeddingService.embedArticle writes it.
    store.add(List.of(
        new Document("Auto merge rules for agent pull requests, verbatim.",
            Map.of("sourceId", "news_" + NOT_HEARTED, "sourceType", "aggregated_article")),
        new Document("Deciding which agent changes may merge without a human.",
            Map.of("sourceId", "news_" + HEARTED, "sourceType", "aggregated_article")),
        new Document("Another chunk of the same hearted article about review gates.",
            Map.of("sourceId", "news_" + HEARTED, "sourceType", "aggregated_article"))));
    lowLevel.performRequest(new Request("POST", "/" + index + "/_refresh"));

    final FavouriteRepository favourites = mock(FavouriteRepository.class);
    when(favourites.findByType(FavouriteType.NEWS)).thenReturn(List.of(
        new Favourite("f1", FavouriteType.NEWS, HEARTED, Instant.parse("2026-10-01T00:00:00Z"))));
    final AggregatedArticleRepository articles = mock(AggregatedArticleRepository.class);
    when(articles.findAllById(anyIterable())).thenAnswer(invocation -> {
      final List<AggregatedArticle> found = new ArrayList<>();
      for (Object id : (Iterable<?>) invocation.getArgument(0)) {
        found.add(new AggregatedArticle((String) id, "Title " + id, "Source",
            "https://source.example", "https://source.example/" + id, "Feed blurb", "Body",
            "Author", Instant.parse("2026-09-30T00:00:00Z"), Instant.now(), true, null));
      }
      return found;
    });
    final ArticleSummaryService summaries = mock(ArticleSummaryService.class);
    when(summaries.findForAll(anyCollection())).thenReturn(Map.of());
    tools = new FavouriteArticleMcpTools(store, favourites, articles, summaries,
        new ShortLinkProperties("https://simonrowe.dev"));
  }

  @Test
  @DisplayName("control: without the filter, the closest chunk is the article that is not hearted")
  void controlTheUnheartedArticleIsTheClosestMatch() {
    final List<Document> unfiltered = store.similaritySearch(
        SearchRequest.builder().query(QUERY).topK(1).build());

    assertThat(unfiltered).extracting(d -> d.getMetadata().get("sourceId"))
        .containsExactly("news_" + NOT_HEARTED);
  }

  @Test
  @DisplayName("only hearted articles come back, once each, however many chunks matched")
  @SuppressWarnings("unchecked")
  void returnsOnlyHeartedArticles() {
    final Map<String, Object> result =
        (Map<String, Object>) tools.searchFavouriteArticles(QUERY, 5);

    final List<Map<String, Object>> found = (List<Map<String, Object>>) result.get("articles");
    assertThat(found).extracting(a -> a.get("id")).containsExactly(HEARTED);
    assertThat(found.getFirst().get("relevance")).isNotNull();
  }

  /**
   * Bag of words plus a large shared component, so every chunk clears the 0.3 threshold and
   * what comes back is decided by the filter, while word overlap still orders the results.
   */
  private static final class BagOfWordsEmbeddingModel implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(final EmbeddingRequest request) {
      final List<String> inputs = request.getInstructions();
      final List<Embedding> embeddings = new ArrayList<>();
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
      for (String word : text.toLowerCase(Locale.ROOT).split("\\W+")) {
        if (!word.isEmpty()) {
          vector[1 + Math.floorMod(word.hashCode(), DIMENSIONS - 1)] += 1f;
        }
      }
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
