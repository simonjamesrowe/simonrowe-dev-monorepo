package com.simonrowe.migration.changeunits;

import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import com.simonrowe.aggregation.newsletter.NewsletterCandidate;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Seeds the four TLDR newsletters as email sources and creates the review ledger's indexes.
 *
 * <p>One source per edition, named exactly as each edition's {@code From} display name, because
 * all four are sent from the same address and the display name is the only thing that tells them
 * apart. The address sits in {@code feedUrl}, which is what {@code EMAIL_NEWSLETTER} reads.
 *
 * <p>No backfill, unlike the site sources: reading the mailbox is not something a change unit
 * should do at boot, and the first scheduled run reads the last three days anyway, which covers
 * every issue received since subscribing on 2026-10-06.
 *
 * <p>The unique {@code url} index is what makes the ledger read each story once; see
 * {@link NewsletterCandidate}. {@code RestoreService} calls {@link #createIndexes} after a
 * restore, because Mongock will not re-run this unit.
 */
@ChangeUnit(id = "seed-tldr-newsletter-sources", order = "051", author = "simonrowe")
public class V051SeedTldrNewsletterSources {

  public static final String URL_INDEX = "idx_newsletter_candidate_url";
  public static final String STATUS_INDEX = "idx_newsletter_candidate_status_relevance";

  static final String SENDER = "dan@tldrnewsletter.com";

  /** Display name and the edition's archive page. */
  static final List<String[]> EDITIONS = List.of(
      new String[] {"TLDR", "https://tldr.tech"},
      new String[] {"TLDR Dev", "https://tldr.tech/dev"},
      new String[] {"TLDR Product", "https://tldr.tech/product"},
      new String[] {"TLDR AI", "https://tldr.tech/ai"});

  @Execution
  public void execution(
      final MongoTemplate mongoTemplate, final ContentSourceRepository sourceRepository) {
    createIndexes(mongoTemplate);
    for (String[] edition : EDITIONS) {
      if (sourceRepository.findByName(edition[0]).isEmpty()) {
        sourceRepository.save(new ContentSource(
            null, edition[0], edition[1], SENDER, null,
            ContentSource.SourceType.NEWS, ContentSource.ScrapeStrategy.EMAIL_NEWSLETTER,
            true, null, null, null));
      }
    }
  }

  @RollbackExecution
  public void rollback(
      final MongoTemplate mongoTemplate, final ContentSourceRepository sourceRepository) {
    for (String[] edition : EDITIONS) {
      sourceRepository.findByName(edition[0])
          .filter(s -> s.scrapeStrategy() == ContentSource.ScrapeStrategy.EMAIL_NEWSLETTER)
          .ifPresent(sourceRepository::delete);
    }
    mongoTemplate.indexOps(NewsletterCandidate.COLLECTION).dropIndex(URL_INDEX);
    mongoTemplate.indexOps(NewsletterCandidate.COLLECTION).dropIndex(STATUS_INDEX);
  }

  public static void createIndexes(final MongoTemplate mongoTemplate) {
    var indexOps = mongoTemplate.indexOps(NewsletterCandidate.COLLECTION);
    indexOps.createIndex(new Index().named(URL_INDEX).on("url", ASC).unique());
    indexOps.createIndex(new Index().named(STATUS_INDEX)
        .on("status", ASC).on("relevance", DESC));
  }
}
