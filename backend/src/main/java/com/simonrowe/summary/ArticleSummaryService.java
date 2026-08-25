package com.simonrowe.summary;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.ArticleSourceTextProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generates and serves the on-demand in-depth summary of an aggregated news article.
 *
 * <p><b>Why this is synchronous.</b> The narration pipeline next door is fully async
 * through Kafka with leases, claims and a recovery scheduler because Google's long-form
 * text-to-speech <em>forces</em> it: the provider returns a long-running operation handle
 * that has to be polled and survive restarts. An LLM call has no such handle. It is a
 * single blocking call of roughly 15-30 seconds, and every other LLM call in this codebase
 * is made inline — {@code ChatService} per request, {@code ContentAggregationAgent} and
 * {@code WeeklyDigestAgent} on scheduler threads. With virtual threads enabled, holding one
 * for 30 seconds is cheap. Reproducing the lease/claim/recovery machinery here would be
 * building infrastructure to track a remote operation that does not exist.
 *
 * <p>The one property that machinery <em>does</em> give for free — never spending twice on
 * the same artefact — is obtained instead from the deterministic {@code _id} and the
 * insert-first guard in {@link #request(String)}.
 */
@Service
public class ArticleSummaryService {

  private static final Logger LOG = LoggerFactory.getLogger(ArticleSummaryService.class);

  private static final Duration STATUS_POLL_INTERVAL = Duration.ofMillis(500);

  /**
   * Versions the prompt below. It feeds the document {@code _id}, so bumping it
   * invalidates every stored summary at once and the next request regenerates.
   *
   * <p><b>Changing {@link #SUMMARY_PROMPT} without bumping this will serve stale
   * summaries forever.</b> The two are deliberately adjacent so that is hard to miss.
   */
  static final String SUMMARY_FORMAT_VERSION = "article-summary-v1";

  private static final String SUMMARY_PROMPT = """
      Write an in-depth summary of the news article below, for a reader deciding \
      whether to read the original.

      Requirements:
      - Neutral third person throughout. This is someone else's article: never write \
      as though you are its author, and never adopt its opinions as your own. \
      Attribute claims to the piece or its author where it matters.
      - Between 4 and 6 paragraphs.
      - Markdown, but do NOT write any heading — the page supplies the heading and the \
      link — and do NOT include links or images.
      - Do NOT restate the title, and do NOT describe the article as an article \
      ("this piece argues that...", "the author begins by..."). Summarise what it \
      actually says.
      - Cover the substance: the argument, the evidence, the specifics. If it contains \
      numbers, names or technical detail that carry the point, keep them.
      - Say nothing the article does not support. If the source text below is partial, \
      summarise only what is there.

      Title: %s
      Source: %s

      Article text:
      %s
      """;

  private final AggregatedArticleRepository articleRepository;
  private final ArticleSummaryRepository summaryRepository;
  private final ArticleSourceTextProvider sourceTextProvider;
  private final Ai ai;
  private final MongoTemplate mongoTemplate;
  private final MeterRegistry meterRegistry;
  private final String model;
  private final Duration generationTimeout;

  public ArticleSummaryService(
      final AggregatedArticleRepository articleRepository,
      final ArticleSummaryRepository summaryRepository,
      final ArticleSourceTextProvider sourceTextProvider,
      final Ai ai,
      final MongoTemplate mongoTemplate,
      final MeterRegistry meterRegistry,
      @Value("${aggregation.summary.model}") final String model,
      @Value("${aggregation.summary.generation-timeout}") final Duration generationTimeout
  ) {
    this.articleRepository = articleRepository;
    this.summaryRepository = summaryRepository;
    this.sourceTextProvider = sourceTextProvider;
    this.ai = ai;
    this.mongoTemplate = mongoTemplate;
    this.meterRegistry = meterRegistry;
    this.model = model;
    this.generationTimeout = generationTimeout;
  }

  /**
   * Requests the summary for an article, generating it if nobody else already has.
   *
   * <p>The flow, in order:
   *
   * <ol>
   *   <li>An existing {@code READY} document is returned as-is — no spend.</li>
   *   <li>An existing non-retryable {@code FAILED} document is returned as-is — no
   *       spend, and crucially no silent retry of something that cannot succeed.</li>
   *   <li>An existing retryable {@code FAILED} document is put back into
   *       {@code GENERATING} and regenerated.</li>
   *   <li>An existing {@code GENERATING} document is reclaimed only if it has gone stale
   *       past {@code generation-timeout}; otherwise the caller is told to poll.</li>
   *   <li>Otherwise an insert is attempted. Winning it means generating; losing it to a
   *       {@link DuplicateKeyException} means someone else is mid-generation.</li>
   * </ol>
   *
   * @param articleId the aggregated article id
   * @return the outcome, with {@code accepted} set when the caller should poll rather than
   *     treat the response as final
   */
  public RequestResult request(final String articleId) {
    AggregatedArticle article = visibleArticle(articleId);
    String id = ArticleSummary.idFor(SUMMARY_FORMAT_VERSION, articleId);

    Optional<ArticleSummary> existing = summaryRepository.findById(id);
    if (existing.isPresent()) {
      return requestExisting(existing.get(), article);
    }

    ArticleSummary summary = new ArticleSummary(id, articleId, Instant.now());
    try {
      summary = summaryRepository.insert(summary);
    } catch (DuplicateKeyException ex) {
      // Another caller inserted between our findById and our insert. They own the
      // generation; we just report the in-progress state.
      meterRegistry.counter("article.summary.requests", "result", "deduped").increment();
      ArticleSummary theirs = summaryRepository.findById(id).orElseThrow();
      return new RequestResult(ArticleSummaryResponse.from(theirs), true);
    }
    return new RequestResult(generate(summary, article), false);
  }

  /**
   * Current state, optionally waiting for it to change.
   *
   * <p>Same contract as {@code BlogNarrationController.getStatus}: returns immediately when
   * {@code afterVersion} is absent, when the version has already moved, when the state is
   * terminal, or when {@code waitSeconds} is zero.
   *
   * @param articleId the aggregated article id
   * @param afterVersion the version the client already has, or null for an immediate read
   * @param waitSeconds how long to hold the request open, bounded by the controller
   * @return the current state
   */
  public ArticleSummaryResponse getStatus(
      final String articleId,
      final Long afterVersion,
      final int waitSeconds
  ) {
    visibleArticle(articleId);
    String id = ArticleSummary.idFor(SUMMARY_FORMAT_VERSION, articleId);
    Instant deadline = Instant.now().plusSeconds(waitSeconds);
    ArticleSummaryResponse response;
    do {
      response = currentResponse(id);
      if (afterVersion == null || response.version() != afterVersion
          || response.isTerminal() || waitSeconds == 0) {
        return response;
      }
      sleepUntilNextPoll(deadline);
    } while (Instant.now().isBefore(deadline));
    return currentResponse(id);
  }

  /**
   * Article ids that already have a completed summary.
   *
   * <p>Mirrors {@code GET /api/favourites/{type}/ids}: it is what lets a logged-out
   * visitor's card read "Read summary" and open instantly, versus "Summarise" which
   * triggers the login popup.
   *
   * @return the ids, empty when nothing has been summarised
   */
  public Set<String> summarisedArticleIds() {
    return summaryRepository.findByStatus(SummaryStatus.READY).stream()
        .map(ArticleSummary::articleId)
        .collect(Collectors.toSet());
  }

  /**
   * The stored summary for an article, whatever its state.
   *
   * @param articleId the aggregated article id
   * @return the summary, or empty when none exists for the current format version
   */
  public Optional<ArticleSummary> findFor(final String articleId) {
    return summaryRepository.findById(
        ArticleSummary.idFor(SUMMARY_FORMAT_VERSION, articleId));
  }

  private RequestResult requestExisting(
      final ArticleSummary summary,
      final AggregatedArticle article
  ) {
    switch (summary.status()) {
      case READY -> {
        meterRegistry.counter("article.summary.requests", "result", "reused").increment();
        return new RequestResult(ArticleSummaryResponse.from(summary), false);
      }
      case FAILED -> {
        if (!summary.retryable()) {
          // Deliberately no regeneration: a non-retryable failure would fail the same way
          // again, and a caller hammering the button must not keep paying for that.
          return new RequestResult(ArticleSummaryResponse.from(summary), false);
        }
        summary.markGenerating(Instant.now());
        summaryRepository.save(summary);
        return new RequestResult(generate(summary, article), false);
      }
      case GENERATING -> {
        Optional<ArticleSummary> reclaimed = reclaimIfStale(summary);
        if (reclaimed.isEmpty()) {
          return new RequestResult(ArticleSummaryResponse.from(summary), true);
        }
        return new RequestResult(generate(reclaimed.get(), article), false);
      }
      default -> throw new IllegalStateException("Unhandled status: " + summary.status());
    }
  }

  /**
   * Reclaims a {@code GENERATING} document whose generating process appears to have died.
   *
   * <p>The filter is guarded on <b>both</b> {@code status} and {@code updatedAt}. Guarding
   * on {@code status} alone would let two concurrent reclaimers both match the same
   * document and both call the model; with {@code updatedAt} in the filter, the first
   * winner's {@code $set} moves the field out from under the loser's query, so exactly one
   * reclaim succeeds.
   */
  private Optional<ArticleSummary> reclaimIfStale(final ArticleSummary summary) {
    Instant now = Instant.now();
    Instant cutoff = now.minus(generationTimeout);
    if (summary.updatedAt() == null || summary.updatedAt().isAfter(cutoff)) {
      return Optional.empty();
    }
    Query query = Query.query(Criteria.where("_id").is(summary.id())
        .and("status").is(SummaryStatus.GENERATING)
        .and("updatedAt").lt(cutoff));
    Update update = new Update()
        .set("updatedAt", now)
        .inc("version", 1);
    ArticleSummary claimed = mongoTemplate.findAndModify(
        query,
        update,
        FindAndModifyOptions.options().returnNew(true),
        ArticleSummary.class);
    if (claimed == null) {
      return Optional.empty();
    }
    meterRegistry.counter("article.summary.requests", "result", "reclaimed").increment();
    LOG.warn("Reclaimed an abandoned summary generation: articleId={}, "
            + "abandonedFor={}s",
        summary.articleId(),
        Duration.between(summary.updatedAt(), now).toSeconds());
    return Optional.of(claimed);
  }

  private ArticleSummaryResponse generate(
      final ArticleSummary summary,
      final AggregatedArticle article
  ) {
    final Instant started = Instant.now();
    String sourceText = sourceTextProvider.sourceTextFor(article);
    if (!ArticleSourceTextProvider.clearsHardFloor(sourceText)) {
      LOG.warn("Not summarising '{}': best available source text is {} characters, "
              + "under the hard floor of {}. Not calling the model.",
          article.title(),
          sourceText == null ? 0 : sourceText.length(),
          ArticleSourceTextProvider.HARD_MIN_SOURCE_CHARS);
      return fail(summary, ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT, false);
    }

    String body;
    try {
      String prompt = String.format(
          SUMMARY_PROMPT, article.title(), article.sourceName(), sourceText);
      body = ai.withLlm(model)
          .respond(List.of(new UserMessage(prompt)))
          .getContent();
    } catch (Exception ex) {
      LOG.warn("Summary generation failed for '{}': {}", article.title(), ex.getMessage());
      return fail(summary, ArticleSummaryFailure.MODEL_ERROR, true);
    }
    if (body == null || body.isBlank()) {
      LOG.warn("Empty completion summarising '{}'", article.title());
      return fail(summary, ArticleSummaryFailure.MODEL_ERROR, true);
    }

    Instant now = Instant.now();
    summary.markReady(body, model, sourceText.length(), now);
    summaryRepository.save(summary);
    meterRegistry.counter("article.summary.requests", "result", "generated").increment();
    meterRegistry.timer("article.summary.generation.duration")
        .record(Duration.between(started, now));
    LOG.info("Article summary ready: articleId={}, sourceCharacters={}, bodyCharacters={}",
        summary.articleId(), sourceText.length(), body.length());
    return ArticleSummaryResponse.from(summary);
  }

  private ArticleSummaryResponse fail(
      final ArticleSummary summary,
      final String code,
      final boolean retryable
  ) {
    summary.markFailed(code, retryable, Instant.now());
    summaryRepository.save(summary);
    meterRegistry.counter("article.summary.requests", "result", "failed",
        "reason", code).increment();
    return ArticleSummaryResponse.from(summary);
  }

  private ArticleSummaryResponse currentResponse(final String id) {
    return summaryRepository.findById(id)
        .map(ArticleSummaryResponse::from)
        .orElseGet(ArticleSummaryResponse::notRequested);
  }

  private AggregatedArticle visibleArticle(final String articleId) {
    return articleRepository.findById(articleId)
        .filter(AggregatedArticle::visible)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Article not found"));
  }

  private static void sleepUntilNextPoll(final Instant deadline) {
    long remaining = Duration.between(Instant.now(), deadline).toMillis();
    if (remaining <= 0) {
      return;
    }
    try {
      Thread.sleep(Math.min(STATUS_POLL_INTERVAL.toMillis(), remaining));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * The outcome of a request.
   *
   * @param response the current state
   * @param accepted true when generation is in someone else's hands and the caller should
   *     poll — surfaced as a 202 rather than a 200
   */
  public record RequestResult(ArticleSummaryResponse response, boolean accepted) {
  }
}
