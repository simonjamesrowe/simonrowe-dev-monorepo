package com.simonrowe.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import com.simonrowe.shortlink.ShortLinkProperties;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryService;
import com.simonrowe.summary.SummaryStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

@ExtendWith(MockitoExtension.class)
class FavouriteArticleMcpToolsTest {

  @Mock
  private VectorStore vectorStore;
  @Mock
  private FavouriteRepository favouriteRepository;
  @Mock
  private AggregatedArticleRepository articleRepository;
  @Mock
  private ArticleSummaryService summaryService;

  private final Map<String, AggregatedArticle> stored = new HashMap<>();
  private FavouriteArticleMcpTools tools;

  @BeforeEach
  void setUp() {
    tools = new FavouriteArticleMcpTools(vectorStore, favouriteRepository, articleRepository,
        summaryService, new ShortLinkProperties("https://simonrowe.dev/"));
  }

  private void heart(final String id, final String heartedAt) {
    stored.put(id, article(id, true));
    favourites.add(new Favourite("f-" + id, FavouriteType.NEWS, id, Instant.parse(heartedAt)));
  }

  private final List<Favourite> favourites = new ArrayList<>();

  private void givenStore() {
    given(favouriteRepository.findByType(FavouriteType.NEWS)).willReturn(favourites);
    given(articleRepository.findAllById(anyIterable())).willAnswer(invocation -> {
      final List<AggregatedArticle> found = new ArrayList<>();
      for (Object id : (Iterable<?>) invocation.getArgument(0)) {
        if (stored.containsKey(id)) {
          found.add(stored.get(id));
        }
      }
      return found;
    });
  }

  private static AggregatedArticle article(final String id, final boolean visible) {
    return new AggregatedArticle(id, "Title " + id, "Tessl", "https://tessl.io",
        "https://tessl.io/blog/" + id, "Feed blurb " + id, "Full text " + id, "Author " + id,
        Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), visible,
        null);
  }

  private static Document chunk(final String articleId, final double score) {
    return Document.builder()
        .text("chunk")
        .metadata(Map.of("sourceId", "news_" + articleId))
        .score(score)
        .build();
  }

  private static ArticleSummary readySummary(final String body) {
    final ArticleSummary summary = org.mockito.Mockito.mock(ArticleSummary.class);
    given(summary.status()).willReturn(SummaryStatus.READY);
    given(summary.body()).willReturn(body);
    return summary;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> articles(final Object result) {
    return (List<Map<String, Object>>) ((Map<String, Object>) result).get("articles");
  }

  @Test
  @DisplayName("search keeps each article once at its best chunk's score, most relevant first")
  void ranksArticlesByTheirBestChunk() {
    heart("a", "2026-10-01T00:00:00Z");
    heart("b", "2026-10-02T00:00:00Z");
    givenStore();
    given(summaryService.findForAll(anyCollection())).willReturn(Map.of());
    given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of(
        chunk("a", 0.61), chunk("b", 0.74), chunk("a", 0.82), chunk("b", 0.40)));

    final List<Map<String, Object>> found =
        articles(tools.searchFavouriteArticles("auto-merge rules", null));

    assertThat(found).extracting(a -> a.get("id")).containsExactly("a", "b");
    assertThat(found).extracting(a -> a.get("relevance")).containsExactly(0.82, 0.74);
  }

  @Test
  @DisplayName("the vector query is filtered to the hearted articles' chunks by exact id")
  void filtersTheQueryToHeartedArticles() {
    heart("a", "2026-10-01T00:00:00Z");
    given(favouriteRepository.findByType(FavouriteType.NEWS)).willReturn(favourites);
    given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of());
    given(summaryService.findForAll(anyCollection())).willReturn(Map.of());

    tools.searchFavouriteArticles("review gates", 3);

    final ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(request.capture());
    assertThat(request.getValue().getQuery()).isEqualTo("review gates");
    assertThat(request.getValue().getTopK())
        .isEqualTo(3 * FavouriteArticleMcpTools.CHUNKS_PER_ARTICLE);
    assertThat(request.getValue().getSimilarityThreshold())
        .isEqualTo(FavouriteArticleMcpTools.SIMILARITY_THRESHOLD);
    assertThat(request.getValue().getFilterExpression().toString())
        .contains("sourceId.keyword", "news_a");
  }

  @Test
  @DisplayName("a long favourites list is searched in batches, never one unbounded filter")
  void batchesLongFavouriteLists() {
    IntStream.range(0, FavouriteArticleMcpTools.ID_BATCH + 1)
        .forEach(i -> heart("id" + i, "2026-10-01T00:00:00Z"));
    givenStore();
    given(vectorStore.similaritySearch(any(SearchRequest.class)))
        .willReturn(List.of(chunk("id3", 0.5)))
        .willReturn(List.of(chunk(String.valueOf("id" + FavouriteArticleMcpTools.ID_BATCH), 0.9)));
    given(summaryService.findForAll(anyCollection())).willReturn(Map.of());

    final List<Map<String, Object>> found =
        articles(tools.searchFavouriteArticles("anything", 5));

    verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    assertThat(found).extracting(a -> a.get("id"))
        .containsExactly("id" + FavouriteArticleMcpTools.ID_BATCH, "id3");
  }

  @Test
  @DisplayName("a ready AI summary is returned and labelled as one, with both links")
  void returnsTheAiSummaryAndLinks() {
    heart("a", "2026-10-01T00:00:00Z");
    givenStore();
    given(vectorStore.similaritySearch(any(SearchRequest.class)))
        .willReturn(List.of(chunk("a", 0.7)));
    final ArticleSummary ready = readySummary("The in-depth summary.");
    given(summaryService.findForAll(anyCollection())).willReturn(Map.of("a", ready));

    final Map<String, Object> found =
        articles(tools.searchFavouriteArticles("merge", null)).getFirst();

    assertThat(found.get("summaryKind")).isEqualTo(FavouriteArticleMcpTools.AI_SUMMARY);
    assertThat(found.get("summary")).isEqualTo("The in-depth summary.");
    assertThat(found.get("articleUrl")).isEqualTo("https://tessl.io/blog/a");
    assertThat(found.get("summaryUrl"))
        .isEqualTo("https://simonrowe.dev/news-events?article=a");
    assertThat(found.get("heartedAt")).isEqualTo("2026-10-01T00:00:00Z");
    assertThat(found).doesNotContainKey("fullContent")
        .doesNotContainValue("Full text a");
  }

  @Test
  @DisplayName("with no ready summary, the feed blurb is returned and labelled as the feed's")
  void fallsBackToTheFeedBlurb() {
    heart("a", "2026-10-01T00:00:00Z");
    givenStore();
    given(vectorStore.similaritySearch(any(SearchRequest.class)))
        .willReturn(List.of(chunk("a", 0.7)));
    given(summaryService.findForAll(anyCollection())).willReturn(Map.of());

    final Map<String, Object> found =
        articles(tools.searchFavouriteArticles("merge", null)).getFirst();

    assertThat(found.get("summaryKind")).isEqualTo(FavouriteArticleMcpTools.FEED_BLURB);
    assertThat(found.get("summary")).isEqualTo("Feed blurb a");
  }

  @Test
  @DisplayName("a blank query lists hearted articles newest-hearted first, without searching")
  void blankQueryListsTheNewestHearted() {
    heart("old", "2026-09-01T00:00:00Z");
    heart("new", "2026-10-20T00:00:00Z");
    heart("mid", "2026-10-01T00:00:00Z");
    givenStore();
    given(summaryService.findForAll(anyCollection())).willReturn(Map.of());

    final Object result = tools.searchFavouriteArticles("  ", 2);

    verifyNoInteractions(vectorStore);
    assertThat(articles(result)).extracting(a -> a.get("id")).containsExactly("new", "mid");
    assertThat(articles(result).getFirst()).doesNotContainKey("relevance");
    assertThat(((Map<?, ?>) result).get("heartedArticles")).isEqualTo(3);
  }

  @Test
  @DisplayName("hidden or deleted articles are skipped, and the limit still fills")
  void skipsHiddenAndMissingArticles() {
    heart("hidden", "2026-10-03T00:00:00Z");
    stored.put("hidden", article("hidden", false));
    heart("gone", "2026-10-02T00:00:00Z");
    stored.remove("gone");
    heart("ok", "2026-10-01T00:00:00Z");
    givenStore();
    given(summaryService.findForAll(anyCollection())).willReturn(Map.of());

    assertThat(articles(tools.searchFavouriteArticles(null, 1)))
        .extracting(a -> a.get("id")).containsExactly("ok");
  }

  @Test
  @DisplayName("listing reads articles in windows sized by the limit, not the whole list")
  @SuppressWarnings("unchecked")
  void listingReadsArticlesInWindowsOfTheLimit() {
    IntStream.range(0, 30).forEach(i -> heart("id" + i,
        Instant.parse("2026-10-01T00:00:00Z").plusSeconds(i).toString()));
    givenStore();
    given(summaryService.findForAll(anyCollection())).willReturn(Map.of());

    assertThat(articles(tools.searchFavouriteArticles("", 2)))
        .extracting(a -> a.get("id")).containsExactly("id29", "id28");

    final ArgumentCaptor<Iterable<String>> ids = ArgumentCaptor.forClass(Iterable.class);
    verify(articleRepository).findAllById(ids.capture());
    assertThat(ids.getValue()).containsExactly("id29", "id28", "id27", "id26");
  }

  @Test
  @DisplayName("the limit is clamped to 1..20")
  void clampsTheLimit() {
    IntStream.range(0, 25).forEach(i -> heart("id" + i, "2026-10-01T00:00:00Z"));
    givenStore();
    given(summaryService.findForAll(anyCollection())).willReturn(Map.of());

    assertThat(articles(tools.searchFavouriteArticles("", 500)))
        .hasSize(FavouriteArticleMcpTools.MAX_LIMIT);
    assertThat(articles(tools.searchFavouriteArticles("", 0))).hasSize(1);
  }

  @Test
  @DisplayName("a vector store failure answers with a message rather than throwing")
  void reportsSearchUnavailable() {
    heart("a", "2026-10-01T00:00:00Z");
    given(favouriteRepository.findByType(FavouriteType.NEWS)).willReturn(favourites);
    given(vectorStore.similaritySearch(any(SearchRequest.class)))
        .willThrow(new IllegalStateException("connection refused"));

    assertThat(tools.searchFavouriteArticles("merge", null))
        .isEqualTo(FavouriteArticleMcpTools.SEARCH_UNAVAILABLE);
  }
}
