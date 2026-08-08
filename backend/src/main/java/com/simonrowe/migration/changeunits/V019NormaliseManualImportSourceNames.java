package com.simonrowe.migration.changeunits;

import com.mongodb.client.MongoCollection;
import com.simonrowe.aggregation.SourceNameResolver;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Folds manually imported articles onto the source they actually belong to.
 *
 * <p>{@code importFromUrl} used to attribute every import to its bare host, so production
 * accumulated eight source names covering ten articles — including "tessl.io" next to
 * "Tessl Blog", and two Anthropic hosts next to "Claude Blog". Each of those is a filter
 * pill on the news page.
 *
 * <p>Scope is enforced in code, not just in this javadoc: only an article whose stored
 * {@code sourceName} is still the bare host of its own {@code originalUrl} — the exact
 * shape the old {@code importFromUrl} minted — is considered. Everything else keeps the
 * name its source gave it, which matters because {@link SourceNameResolver#resolve} is a
 * <em>naming</em> function for URLs with no name yet, not a rewriting function: a source
 * whose articles legitimately live on other hosts (AI4JVM's curated roundup) or whose
 * host is shared/unknown would otherwise be renamed to a host name en masse.
 *
 * <p>Of the in-scope articles, only those whose host resolves to a source we already
 * track are rewritten; genuinely separate publishers keep their host name, which is
 * accurate attribution on the card badge, and the news page hides the low-volume ones
 * behind a "More" overflow.
 *
 * <p>{@code sourceUrl} is intentionally left alone: nothing filters or displays it, and
 * rewriting it would claim the article came from a page it did not.
 *
 * <p>Idempotent: an article whose {@code sourceName} already equals the resolved name is
 * skipped, so a re-run is a no-op. Works at the raw {@link Document} level to avoid a
 * record round-trip rewriting fields this migration has no business touching.
 */
@ChangeUnit(id = "normalise-manual-import-source-names", order = "019", author = "simonrowe")
public class V019NormaliseManualImportSourceNames {

  private static final Logger log =
      LoggerFactory.getLogger(V019NormaliseManualImportSourceNames.class);

  private static final String ARTICLES = "aggregated_articles";
  private static final String SOURCE_NAME = "sourceName";
  private static final String ORIGINAL_URL = "originalUrl";

  @Execution
  public void execution(
      final MongoTemplate mongoTemplate,
      final SourceNameResolver sourceNameResolver) {
    final MongoCollection<Document> articles = mongoTemplate.getCollection(ARTICLES);

    int rewritten = 0;
    for (final Document article : articles.find()) {
      final String originalUrl = article.getString(ORIGINAL_URL);
      final String currentName = article.getString(SOURCE_NAME);
      if (originalUrl == null || currentName == null) {
        continue;
      }
      // Only articles still carrying the host name `importFromUrl` used to mint are in
      // scope. Anything else — AI4JVM's curated cross-host items, every article written by
      // the scheduled aggregation — keeps the name its source gave it. `hostOf` lowercases
      // and strips `www.` exactly as the old `extractHostName` did, so this matches the
      // legacy rows precisely.
      if (!currentName.equals(SourceNameResolver.hostOf(originalUrl))) {
        continue;
      }
      final String resolved = sourceNameResolver.resolve(originalUrl);
      if (resolved.equals(currentName)) {
        continue;
      }
      articles.updateOne(
          new Document("_id", article.get("_id")),
          new Document("$set", new Document(SOURCE_NAME, resolved)));
      log.info("Re-attributed '{}' from {} to {}", originalUrl, currentName, resolved);
      rewritten++;
    }
    log.info("Re-attributed {} articles to their known source", rewritten);
  }

  /**
   * Deliberately empty. The pre-migration source name was derived from each article's
   * host and is not recorded anywhere, so it cannot be restored; re-deriving it would
   * simply undo the fix. Rolling back leaves the corrected names in place, which is
   * harmless — the names are display metadata, not identity.
   */
  @RollbackExecution
  public void rollback() {
    log.info("No rollback for source-name normalisation; corrected names are kept");
  }
}
