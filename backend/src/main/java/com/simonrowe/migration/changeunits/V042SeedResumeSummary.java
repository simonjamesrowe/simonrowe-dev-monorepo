package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Seeds the CV's summary paragraph.
 *
 * <p>The CV renders {@code profile.resumeSummary} above the experience section, falling
 * back to the site headline when it is unset. The headline is a tagline stored upper
 * case for the site's own styling — usable, but not what a reader of a CV is looking
 * for — so the field ships with a draft rather than empty. It is editable in the admin
 * profile page from the moment this runs.
 *
 * <p>Idempotent and non-destructive: the summary is only written when the field is
 * absent or blank, so a re-run never overwrites an edit made through the CMS.
 */
@ChangeUnit(id = "seed-resume-summary", order = "042", author = "simonrowe")
public class V042SeedResumeSummary {

  private static final String COLLECTION = "profiles";
  private static final String FIELD = "resumeSummary";

  private static final String SUMMARY = """
      Engineering leader with over two decades across investment banking, media and \
      fin-tech. Currently Head of Engineering for Commercial Trading at Global, \
      Europe's largest media and entertainment group, leading 30+ engineers across \
      three product pillars. Consistent track record of moving teams from monolith to \
      cloud-native — Kubernetes, Kafka and event-driven architecture at scale — and an \
      early, deliberate adopter of AI-native engineering, introducing Claude Code, MCP \
      security patterns and structured evaluation frameworks across the department.""";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    final var collection = mongoTemplate.getCollection(COLLECTION);

    for (final Document profile : collection.find()) {
      final String existing = profile.getString(FIELD);
      if (existing != null && !existing.isBlank()) {
        continue;
      }
      collection.updateOne(
          new Document("_id", profile.get("_id")),
          new Document("$set", new Document(FIELD, SUMMARY)));
    }
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    final var collection = mongoTemplate.getCollection(COLLECTION);

    // Only unset the seeded text. An edit made since is the operator's, not ours.
    collection.updateMany(
        new Document(FIELD, SUMMARY),
        new Document("$unset", new Document(FIELD, "")));
  }
}
