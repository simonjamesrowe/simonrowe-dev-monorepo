package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Shortens the names of the two GitHub social links so they are distinguishable in a
 * label.
 *
 * <p>Both links already carried a name ("Personal Github Account" and "Public org for all
 * repos that make up www.simonjamesrowe.com"), but they were too long to display and the
 * frontend was ignoring them in favour of a platform-derived label, so both rendered as
 * "GitHub". The frontend now prefers the stored name; this migration makes those names
 * short enough to use.
 *
 * <p>Matched on the link URL rather than on position, since document order is not
 * guaranteed. Idempotent: a document is only written when its name actually differs from
 * the target, so a re-run is a no-op and a later manual rename is not clobbered.
 */
@ChangeUnit(id = "name-github-social-links", order = "017", author = "simonrowe")
public class V017NameGithubSocialLinks {

  private static final String COLLECTION = "social_medias";
  private static final String GITHUB = "github";

  /** Target name keyed by the link URL it belongs to. */
  private static final Map<String, String> NAMES_BY_LINK = Map.of(
      "https://github.com/simonrowe", "GitHub — personal",
      "https://github.com/simonjamesrowe", "GitHub — this site");

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    final var collection = mongoTemplate.getCollection(COLLECTION);

    for (final Document link : collection.find(new Document("type", GITHUB))) {
      final String target = NAMES_BY_LINK.get(normalise(link.getString("link")));
      if (target == null || target.equals(link.getString("name"))) {
        continue;
      }
      collection.updateOne(
          new Document("_id", link.get("_id")),
          new Document("$set", new Document("name", target)));
    }
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    final var collection = mongoTemplate.getCollection(COLLECTION);

    for (final Document link : collection.find(new Document("type", GITHUB))) {
      if (NAMES_BY_LINK.containsKey(normalise(link.getString("link")))) {
        collection.updateOne(
            new Document("_id", link.get("_id")),
            new Document("$unset", new Document("name", "")));
      }
    }
  }

  /** Trims and strips any trailing slash so a stored URL still matches the manifest. */
  private String normalise(final String link) {
    if (link == null) {
      return "";
    }
    final String trimmed = link.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
