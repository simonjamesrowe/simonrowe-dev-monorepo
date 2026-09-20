package com.simonrowe.migration.changeunits;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Copies any legacy CoParent Mongo collections into the dedicated database while preserving every
 * BSON identifier, timestamp and embedded record. Existing identical target documents are skipped;
 * a conflicting target identifier aborts startup rather than overwriting family data. Those guards
 * make the migration idempotent and safe to re-run.
 */
@ChangeUnit(id = "migrate-coparent-data", order = "044", author = "simonrowe")
public class V044MigrateCoparentData {

  private static final Logger log = LoggerFactory.getLogger(V044MigrateCoparentData.class);

  private static final Map<String, String> COLLECTIONS = collectionMapping();

  @Execution
  public void execution(
      final CoparentMongoOperations operations,
      final MongoClient mongoClient,
      final CoparentProperties properties) {
    final MongoTemplate mongoTemplate = operations.template();
    final MongoDatabase source = mongoClient.getDatabase(properties.sourceDatabase());
    final MongoDatabase target = mongoTemplate.getDb();
    if (source.getName().equals(target.getName())) {
      log.info("CoParent source database is the target database; skipping legacy copy");
      return;
    }

    final Set<String> sourceNames = StreamSupport.stream(
        source.listCollectionNames().spliterator(), false).collect(Collectors.toSet());
    if (sourceNames.stream().noneMatch(COLLECTIONS::containsKey)) {
      log.info("No legacy CoParent collections found in database '{}'; nothing to migrate",
          source.getName());
      return;
    }

    int inserted = 0;
    int unchanged = 0;
    for (final Map.Entry<String, String> mapping : COLLECTIONS.entrySet()) {
      if (!sourceNames.contains(mapping.getKey())) {
        continue;
      }
      final MongoCollection<Document> sourceCollection = source.getCollection(mapping.getKey());
      final MongoCollection<Document> targetCollection = target.getCollection(mapping.getValue());
      for (final Document sourceDocument : sourceCollection.find()) {
        final Document existing = targetCollection.find(
            new Document("_id", sourceDocument.get("_id"))).first();
        if (existing == null) {
          targetCollection.insertOne(new Document(sourceDocument));
          inserted++;
        } else if (existing.equals(sourceDocument)) {
          unchanged++;
        } else {
          throw new IllegalStateException("Conflicting CoParent document in "
              + mapping.getValue() + " for id " + sourceDocument.get("_id"));
        }
      }
    }
    log.info("Migrated {} CoParent documents; {} were already identical", inserted, unchanged);
  }

  private static Map<String, String> collectionMapping() {
    final Map<String, String> mapping = new LinkedHashMap<>();
    mapping.put(V043CreateCoparentCollections.FAMILIES, V043CreateCoparentCollections.FAMILIES);
    mapping.put(V043CreateCoparentCollections.PARENTS, V043CreateCoparentCollections.PARENTS);
    mapping.put(V043CreateCoparentCollections.CHILDREN, V043CreateCoparentCollections.CHILDREN);
    mapping.put(V043CreateCoparentCollections.INVITATIONS,
        V043CreateCoparentCollections.INVITATIONS);
    mapping.put(V043CreateCoparentCollections.ONBOARDING, V043CreateCoparentCollections.ONBOARDING);
    mapping.put(V043CreateCoparentCollections.EVENTS, V043CreateCoparentCollections.EVENTS);
    mapping.put(V043CreateCoparentCollections.CATEGORIES, V043CreateCoparentCollections.CATEGORIES);
    mapping.put(V043CreateCoparentCollections.SCHEDULE_CHANGES,
        V043CreateCoparentCollections.SCHEDULE_CHANGES);
    mapping.put(V043CreateCoparentCollections.CONVERSATIONS,
        V043CreateCoparentCollections.CONVERSATIONS);
    mapping.put(V043CreateCoparentCollections.AUDITS, V043CreateCoparentCollections.AUDITS);
    return Map.copyOf(mapping);
  }

  @RollbackExecution
  public void rollback() {
    // Migrated family data is retained. Disable CoParent or restore the pre-cutover backup instead.
  }
}
