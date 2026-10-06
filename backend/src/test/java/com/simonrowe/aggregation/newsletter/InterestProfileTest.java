package com.simonrowe.aggregation.newsletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.newsletter.InterestProfile.Relevance;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class InterestProfileTest {

  @Mock private EmbeddingModel embeddingModel;
  @Mock private FavouriteRepository favouriteRepository;
  @Mock private AggregatedArticleRepository articleRepository;

  private InterestProfile profile;

  @BeforeEach
  void setUp() {
    ObjectProvider<EmbeddingModel> provider = mock();
    lenient().when(provider.getIfAvailable()).thenReturn(embeddingModel);
    profile = new InterestProfile(provider, favouriteRepository, articleRepository);
  }

  private static Favourite heart(final String articleId) {
    return new Favourite("f-" + articleId, FavouriteType.NEWS, articleId, Instant.now());
  }

  private static AggregatedArticle article(final String id, final String title) {
    return new AggregatedArticle(id, title, "Claude Blog", null, "https://x/" + id, "summary",
        null, null, Instant.now(), Instant.now(), true, null);
  }

  @Test
  void withNothingHeartedEverythingScoresZeroAndNothingIsEmbedded() {
    when(favouriteRepository.findByType(FavouriteType.NEWS)).thenReturn(List.of());
    when(articleRepository.findAllById(anyList())).thenReturn(List.of());

    List<Relevance> scores = profile.score(List.of("a story", "another"));

    assertThat(scores).containsExactly(Relevance.NO_PROFILE, Relevance.NO_PROFILE);
    verify(embeddingModel, never()).embed(anyList());
  }

  @Test
  void scoresEachTextByItsClosestHeartedArticle() {
    when(favouriteRepository.findByType(FavouriteType.NEWS))
        .thenReturn(List.of(heart("a1"), heart("a2")));
    when(articleRepository.findAllById(anyList()))
        .thenReturn(List.of(article("a1", "Spring AI 2.0"), article("a2", "Claude Code")));
    when(embeddingModel.embed(anyList()))
        .thenReturn(List.of(new float[] {1, 0}, new float[] {0, 1}))
        .thenReturn(List.of(new float[] {0.2f, 0.98f}, new float[] {-1, 0}));

    List<Relevance> scores = profile.score(List.of("near claude", "opposite of spring"));

    assertThat(scores.get(0).nearestTitle()).isEqualTo("Claude Code");
    assertThat(scores.get(0).score()).isGreaterThan(0.95);
    // Negative similarity is floored at zero rather than reported as a score.
    assertThat(scores.get(1).score()).isZero();
  }

  @Test
  void embedsEachHeartedArticleOnceAcrossRuns() {
    when(favouriteRepository.findByType(FavouriteType.NEWS)).thenReturn(List.of(heart("a1")));
    when(articleRepository.findAllById(List.of("a1")))
        .thenReturn(List.of(article("a1", "Spring AI 2.0")));
    when(articleRepository.findAllById(List.of())).thenReturn(List.of());
    when(embeddingModel.embed(anyList()))
        .thenReturn(List.of(new float[] {1, 0}))
        .thenReturn(List.of(new float[] {1, 0}))
        .thenReturn(List.of(new float[] {1, 0}));

    profile.score(List.of("first run"));
    profile.score(List.of("second run"));

    // One call for the profile, then one per run for the stories.
    verify(embeddingModel, times(3)).embed(anyList());
  }

  @Test
  void cosineHandlesZeroVectors() {
    assertThat(InterestProfile.cosine(new float[] {0, 0}, new float[] {1, 0})).isZero();
    assertThat(InterestProfile.cosine(new float[] {3, 4}, new float[] {3, 4})).isEqualTo(1.0,
        org.assertj.core.data.Offset.offset(1e-9));
  }
}
