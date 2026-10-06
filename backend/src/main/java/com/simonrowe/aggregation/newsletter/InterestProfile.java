package com.simonrowe.aggregation.newsletter;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Scores text against what Simon has hearted on News &amp; Events.
 *
 * <p>The profile is the hearted articles themselves, each embedded once and held in memory, and a
 * story's score is its best cosine similarity to any one of them. Best rather than average,
 * because the hearts span several unrelated interests (Spring releases, Claude Code, agent
 * evaluation) and a story only needs to be close to one of them; an average punishes exactly the
 * stories that sit squarely inside a single interest.
 *
 * <p>Embeddings use the application's {@code EmbeddingModel} ({@code text-embedding-3-small}),
 * about a tenth of a cent per thousand stories. Hearting or un-hearting changes the next run's
 * scores with nothing else to do. Favourites are global on this site, not per user, so "what
 * Simon hearted" is simply the whole collection.
 */
@Component
public class InterestProfile {

  /** Keeps one long summary from dominating the request; titles carry most of the signal. */
  private static final int MAX_TEXT_CHARS = 2000;

  private final ObjectProvider<EmbeddingModel> embeddingModel;
  private final FavouriteRepository favouriteRepository;
  private final AggregatedArticleRepository articleRepository;
  private final Map<String, Interest> cache = new ConcurrentHashMap<>();

  /**
   * Takes the model through a provider, as {@code SchoolVectorStoreConfig} does: contexts without
   * an embedding model (tests, the AOT training profile) must still start, and a newsletter run
   * in one of them fails with a clear message rather than the application failing to boot.
   */
  public InterestProfile(
      final ObjectProvider<EmbeddingModel> embeddingModel,
      final FavouriteRepository favouriteRepository,
      final AggregatedArticleRepository articleRepository) {
    this.embeddingModel = embeddingModel;
    this.favouriteRepository = favouriteRepository;
    this.articleRepository = articleRepository;
  }

  /** How close a text is to the profile, and to which hearted article. */
  public record Relevance(double score, String nearestTitle) {

    /** The score when nothing is hearted: nothing can be judged relevant. */
    static final Relevance NO_PROFILE = new Relevance(0, null);
  }

  private record Interest(String title, float[] vector) {
  }

  /**
   * Scores each text.
   *
   * <p>With nothing hearted every text scores zero, so nothing is accepted automatically and
   * everything waits for review. That is deliberate: a newsletter source with no profile to
   * filter on would otherwise have to choose between saving everything and saving nothing.
   *
   * @param texts the texts, typically headline plus summary
   * @return one relevance per text, in the same order
   * @throws RuntimeException when the embedding call fails, so the caller stores nothing unscored
   */
  public List<Relevance> score(final List<String> texts) {
    if (texts.isEmpty()) {
      return List.of();
    }
    final List<Interest> profile = refresh();
    if (profile.isEmpty()) {
      return texts.stream().map(t -> Relevance.NO_PROFILE).toList();
    }
    final List<float[]> vectors = model().embed(texts.stream().map(this::clip).toList());
    final List<Relevance> results = new ArrayList<>(vectors.size());
    for (float[] vector : vectors) {
      Interest nearest = null;
      double best = Double.NEGATIVE_INFINITY;
      for (Interest interest : profile) {
        final double similarity = cosine(vector, interest.vector());
        if (similarity > best) {
          best = similarity;
          nearest = interest;
        }
      }
      results.add(new Relevance(Math.max(0, best), nearest.title()));
    }
    return results;
  }

  /** Embeds any newly hearted article and forgets any that are no longer hearted. */
  private List<Interest> refresh() {
    final Set<String> ids = favouriteRepository.findByType(FavouriteType.NEWS).stream()
        .map(Favourite::contentId)
        .collect(Collectors.toSet());
    cache.keySet().retainAll(ids);
    final List<AggregatedArticle> missing = new ArrayList<>();
    articleRepository.findAllById(ids.stream().filter(id -> !cache.containsKey(id)).toList())
        .forEach(missing::add);
    if (!missing.isEmpty()) {
      final List<float[]> vectors = model().embed(missing.stream()
          .map(article -> clip("%s. %s".formatted(
              article.title(), article.summary() == null ? "" : article.summary())))
          .toList());
      for (int i = 0; i < missing.size(); i++) {
        cache.put(missing.get(i).id(), new Interest(missing.get(i).title(), vectors.get(i)));
      }
    }
    return List.copyOf(cache.values());
  }

  private EmbeddingModel model() {
    final EmbeddingModel model = embeddingModel.getIfAvailable();
    if (model == null) {
      throw new IllegalStateException("No embedding model is configured to score relevance");
    }
    return model;
  }

  private String clip(final String text) {
    return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
  }

  static double cosine(final float[] a, final float[] b) {
    final int length = Math.min(a.length, b.length);
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    if (normA == 0 || normB == 0) {
      return 0;
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }
}
