package com.simonrowe.mcp;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import com.simonrowe.shortlink.ShortLinkContentType;
import com.simonrowe.shortlink.ShortLinkProperties;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryService;
import com.simonrowe.summary.SummaryStatus;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * Research tools over the news articles Simon has hearted, for an external agent on the public
 * {@code /mcp}.
 *
 * <p>Read-only, and deliberately not given to the site's own chat ({@code ChatConfig} passes
 * {@link ProfileMcpTools} alone): every result carries a whole summary, which is the point for a
 * research agent and needless context for a visitor's question. Everything returned is already
 * public: favourites, summaries and articles are all served openly by the news page. The
 * article's full text is not returned; the agent is given the publisher's link instead.
 */
@Component
public class FavouriteArticleMcpTools {

  private static final Logger LOG = LoggerFactory.getLogger(FavouriteArticleMcpTools.class);

  static final int DEFAULT_LIMIT = 8;
  static final int MAX_LIMIT = 20;
  /** Same floor as the site chat's retrieval, so "relevant" means the same thing in both. */
  static final double SIMILARITY_THRESHOLD = 0.3;
  /** An article is split into several chunks, so ask for more chunks than articles wanted. */
  static final int CHUNKS_PER_ARTICLE = 4;
  /**
   * Hearted ids sent per vector query. The store's filter becomes one Lucene clause per id, and
   * Elasticsearch caps the clauses in a query, so a long favourites list is searched in batches
   * and merged by score rather than trusted to stay short (96 on 2026-11-04).
   */
  static final int ID_BATCH = 500;

  static final String SOURCE_ID_PREFIX = "news_";
  /**
   * The exact-match sub-field Elasticsearch maps for the store's dynamic metadata. The plain
   * {@code sourceId} is an analysed text field, where a quoted id is a phrase match that happens
   * to work for today's id shape; the keyword field matches the id and nothing else.
   */
  static final String SOURCE_ID_KEY = "sourceId.keyword";

  static final String AI_SUMMARY = "ai-summary";
  static final String FEED_BLURB = "feed-blurb";
  static final String CITATION_GUIDANCE = """
      summaryKind "ai-summary" is a summary written by an AI model for simonrowe.dev from the \
      article, not the author's own words: cite it as "AI summary of <title> (<source>)" and \
      link articleUrl. "feed-blurb" is the publisher's own feed excerpt. Read articleUrl before \
      attributing a position to the author.""";
  static final String SEARCH_UNAVAILABLE =
      "Search is temporarily unavailable. Please try again later.";

  private final VectorStore vectorStore;
  private final FavouriteRepository favouriteRepository;
  private final AggregatedArticleRepository articleRepository;
  private final ArticleSummaryService summaryService;
  private final ShortLinkProperties siteProperties;

  public FavouriteArticleMcpTools(
      final VectorStore vectorStore,
      final FavouriteRepository favouriteRepository,
      final AggregatedArticleRepository articleRepository,
      final ArticleSummaryService summaryService,
      final ShortLinkProperties siteProperties) {
    this.vectorStore = vectorStore;
    this.favouriteRepository = favouriteRepository;
    this.articleRepository = articleRepository;
    this.summaryService = summaryService;
    this.siteProperties = siteProperties;
  }

  /**
   * Searches the hearted articles by meaning, or lists them newest-hearted first.
   *
   * @param query what to look for; blank lists the most recently hearted
   * @param limit how many articles to return, 1 to {@value #MAX_LIMIT}
   * @return the matches with their summaries and links, or a message when search is down
   */
  @WithSpan
  @Tool(description = """
      Semantic (vector) search over the tech news articles Simon has hearted (liked) on \
      simonrowe.dev, his curated reading collection. Returns each matching article with its \
      in-depth summary, a link to the full article, its source, author and dates, and a \
      relevance score, most relevant first. Use this to research a topic, gather evidence \
      and form an opinion grounded in the articles Simon has chosen, citing each one. Pass a \
      blank query to list the most recently hearted articles instead.""")
  public Object searchFavouriteArticles(
      @ToolParam(description = """
          What to research, in natural language, e.g. "rules for auto-merging pull requests \
          opened by agents". Blank lists the most recently hearted articles.""")
      final String query,
      @ToolParam(required = false, description = "How many articles to return, 1 to 20; default 8.")
      final Integer limit) {
    final int wanted = clamp(limit);
    final Map<String, Favourite> hearted = favouriteRepository.findByType(FavouriteType.NEWS)
        .stream()
        .collect(Collectors.toMap(Favourite::contentId, Function.identity(),
            (first, second) -> first, LinkedHashMap::new));

    final List<Ranked> ranked;
    if (query == null || query.isBlank()) {
      ranked = hearted.values().stream()
          .sorted(Comparator.comparing(Favourite::createdAt,
              Comparator.nullsLast(Comparator.reverseOrder())))
          .map(favourite -> new Ranked(favourite.contentId(), null))
          .toList();
    } else {
      try {
        ranked = search(query.strip(), List.copyOf(hearted.keySet()), wanted);
      } catch (RuntimeException e) {
        LOG.warn("Favourite article search failed: {}", e.toString());
        return SEARCH_UNAVAILABLE;
      }
    }

    final Map<String, AggregatedArticle> articles = new LinkedHashMap<>();
    final List<Ranked> shown = firstVisible(ranked, wanted, articles);
    final Map<String, ArticleSummary> summaries = summaryService.findForAll(
        shown.stream().map(Ranked::articleId).toList());

    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("heartedArticles", hearted.size());
    result.put("returned", shown.size());
    result.put("citationGuidance", CITATION_GUIDANCE);
    result.put("articles", shown.stream()
        .map(r -> toResult(r, articles.get(r.articleId()), hearted.get(r.articleId()),
            summaries.get(r.articleId())))
        .toList());
    return result;
  }

  private List<Ranked> search(final String query, final List<String> heartedIds,
      final int wanted) {
    final Map<String, Double> best = new LinkedHashMap<>();
    final int topK = wanted * CHUNKS_PER_ARTICLE;
    for (int from = 0; from < heartedIds.size(); from += ID_BATCH) {
      final Object[] sourceIds = heartedIds
          .subList(from, Math.min(from + ID_BATCH, heartedIds.size()))
          .stream()
          .map(id -> SOURCE_ID_PREFIX + id)
          .toArray();
      final FilterExpressionBuilder filter = new FilterExpressionBuilder();
      final List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
          .query(query)
          .topK(topK)
          .similarityThreshold(SIMILARITY_THRESHOLD)
          .filterExpression(filter.in(SOURCE_ID_KEY, sourceIds).build())
          .build());
      // Defensive: the store's return value is third-party behaviour that varies by version.
      if (documents == null) {
        continue;
      }
      for (Document document : documents) {
        final Object sourceId = document.getMetadata().get("sourceId");
        if (sourceId instanceof String id && id.startsWith(SOURCE_ID_PREFIX)) {
          final double score = document.getScore() == null ? 0.0 : document.getScore();
          best.merge(id.substring(SOURCE_ID_PREFIX.length()), score, Math::max);
        }
      }
    }
    final List<Ranked> ranked = new ArrayList<>();
    best.forEach((id, score) -> ranked.add(new Ranked(id, score)));
    ranked.sort(Comparator.comparing(Ranked::score, Comparator.reverseOrder()));
    return ranked;
  }

  /**
   * The first {@code wanted} candidates whose article still exists and is visible, in rank
   * order, loading articles a window at a time so the reads grow with the limit rather than
   * with the whole favourites list. A window is twice the limit, so the usual case is one read
   * and a few hidden or deleted articles still leave the limit filled.
   */
  private List<Ranked> firstVisible(final List<Ranked> ranked, final int wanted,
      final Map<String, AggregatedArticle> loaded) {
    final List<Ranked> shown = new ArrayList<>();
    final int window = wanted * 2;
    for (int from = 0; from < ranked.size() && shown.size() < wanted; from += window) {
      final List<Ranked> page = ranked.subList(from, Math.min(from + window, ranked.size()));
      articleRepository.findAllById(page.stream().map(Ranked::articleId).toList())
          .forEach(article -> loaded.put(article.id(), article));
      for (Ranked candidate : page) {
        final AggregatedArticle article = loaded.get(candidate.articleId());
        if (shown.size() < wanted && article != null && article.visible()) {
          shown.add(candidate);
        }
      }
    }
    return shown;
  }

  private Map<String, Object> toResult(final Ranked ranked, final AggregatedArticle article,
      final Favourite favourite, final ArticleSummary summary) {
    final boolean summarised = summary != null && summary.status() == SummaryStatus.READY
        && summary.body() != null && !summary.body().isBlank();
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", article.id());
    map.put("title", article.title());
    map.put("sourceName", article.sourceName());
    map.put("author", article.author());
    map.put("publishedDate", article.publishedDate() == null ? null
        : article.publishedDate().toString());
    map.put("heartedAt", favourite.createdAt() == null ? null
        : favourite.createdAt().toString());
    if (ranked.score() != null) {
      map.put("relevance", Math.round(ranked.score() * 1000) / 1000.0);
    }
    map.put("articleUrl", article.originalUrl());
    map.put("summaryUrl",
        siteProperties.absolute(ShortLinkContentType.ARTICLE.destinationPath(article.id())));
    map.put("summaryKind", summarised ? AI_SUMMARY : FEED_BLURB);
    map.put("summary", summarised ? summary.body() : article.summary());
    return map;
  }

  private static int clamp(final Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    return Math.max(1, Math.min(MAX_LIMIT, limit));
  }

  /** One article and its best chunk's score; a null score means it was listed, not searched. */
  private record Ranked(String articleId, Double score) {
  }
}
