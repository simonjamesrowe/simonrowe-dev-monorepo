package com.simonrowe.narration;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryService;
import com.simonrowe.summary.SummaryStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Narration source for the generated in-depth summary of an aggregated news article.
 *
 * <p>The {@code contentId} is the <em>article</em> id, not the summary id: that is what the
 * URL carries and what a caller knows. The summary is looked up from it.
 *
 * <p>Only the summary is narrated, never the article's own body. Scraped article text runs
 * from full page content to a bare RSS snippet, so "play the full article" is a promise the
 * feature could not keep; and hosting a complete audio rendition of a third party's article
 * would be a reproduction rather than a transformation. Narrating our own summary avoids
 * the question entirely.
 *
 * <p>Because the fingerprint is computed over the summary text, regenerating a summary
 * yields a different narration id and marks the previous audio {@code STALE} for free.
 */
@Component
public class ArticleSummaryNarrationSource implements NarrationSource {

  private static final String AUDIO_ENCODING = "MP3";

  private final AggregatedArticleRepository articleRepository;
  private final ArticleSummaryService summaryService;
  private final NarrationScriptBuilder scriptBuilder;
  private final NarrationProperties properties;

  public ArticleSummaryNarrationSource(
      final AggregatedArticleRepository articleRepository,
      final ArticleSummaryService summaryService,
      final NarrationScriptBuilder scriptBuilder,
      final NarrationProperties properties
  ) {
    this.articleRepository = articleRepository;
    this.summaryService = summaryService;
    this.scriptBuilder = scriptBuilder;
    this.properties = properties;
  }

  @Override
  public NarrationContentType contentType() {
    return NarrationContentType.ARTICLE_SUMMARY;
  }

  @Override
  public NarrationDescriptor scriptFor(final String contentId) {
    AggregatedArticle article = articleRepository.findById(contentId)
        .filter(AggregatedArticle::visible)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Article not found"));
    ArticleSummary summary = summaryService.findFor(contentId)
        .filter(stored -> stored.status() == SummaryStatus.READY)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "No summary to narrate for this article"));

    // Same builder blogs use: the Markdown stripping was never blog-specific.
    String script = scriptBuilder.build(article.title(), summary.body());
    if (script.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Summary has no narratable prose");
    }
    if (script.length() > properties.maxBlogCharacters()) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "Summary is too long to narrate");
    }
    String id = scriptBuilder.fingerprint(
        script,
        properties.voiceName(),
        properties.languageCode(),
        AUDIO_ENCODING);
    return new NarrationDescriptor(id, script);
  }

  @Override
  public boolean isCurrent(final Narration narration) {
    try {
      return scriptFor(narration.contentId()).id().equals(narration.id());
    } catch (ResponseStatusException ex) {
      // Article gone, hidden, or its summary regenerated away — nothing current here.
      return false;
    }
  }
}
