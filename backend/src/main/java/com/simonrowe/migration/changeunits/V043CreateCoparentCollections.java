package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates CoParent's collections in its dedicated database and adds query/uniqueness indexes
 * because automatic index creation is disabled. Every operation is idempotent, making this unit
 * safe to re-run directly after a restore.
 */
@ChangeUnit(id = "create-coparent-collections", order = "043", author = "simonrowe")
public class V043CreateCoparentCollections {

  private static final Logger log = LoggerFactory.getLogger(V043CreateCoparentCollections.class);

  public static final String FAMILIES = "families";
  public static final String PARENTS = "parents";
  public static final String CHILDREN = "children";
  public static final String INVITATIONS = "invitations";
  public static final String ONBOARDING = "onboardingstates";
  public static final String EVENTS = "events";
  public static final String CATEGORIES = "eventcategories";
  public static final String SCHEDULE_CHANGES = "schedulechangerequests";
  public static final String CONVERSATIONS = "conversations";
  public static final String AUDITS = "audits";

  private static final List<String> COLLECTIONS = List.of(
      FAMILIES, PARENTS, CHILDREN, INVITATIONS, ONBOARDING, EVENTS, CATEGORIES,
      SCHEDULE_CHANGES, CONVERSATIONS, AUDITS);

  @Execution
  public void execution(final CoparentMongoOperations operations) {
    createIndexes(operations.template());
    log.info("Ensured {} CoParent collections and their indexes", COLLECTIONS.size());
  }

  /** Recreates all CoParent schema indexes after a collection-dropping restore. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    COLLECTIONS.stream()
        .filter(collection -> !mongoTemplate.collectionExists(collection))
        .forEach(mongoTemplate::createCollection);

    mongoTemplate.indexOps(FAMILIES).createIndex(index("idx_coparent_family_deleted")
        .on("deletedAt", Sort.Direction.ASC));

    mongoTemplate.indexOps(PARENTS).createIndex(index("idx_coparent_parent_subject")
        .on("auth0Id", Sort.Direction.ASC));
    mongoTemplate.indexOps(PARENTS).createIndex(index("idx_coparent_parent_family_subject")
        .on("familyId", Sort.Direction.ASC)
        .on("auth0Id", Sort.Direction.ASC)
        .unique()
        .sparse());

    mongoTemplate.indexOps(CHILDREN).createIndex(index("idx_coparent_child_family_active")
        .on("familyId", Sort.Direction.ASC)
        .on("deletedAt", Sort.Direction.ASC));

    mongoTemplate.indexOps(INVITATIONS).createIndex(index("idx_coparent_invitation_token")
        .on("token", Sort.Direction.ASC).unique());
    mongoTemplate.indexOps(INVITATIONS).createIndex(index("idx_coparent_invitation_family_status")
        .on("familyId", Sort.Direction.ASC).on("status", Sort.Direction.ASC));
    mongoTemplate.indexOps(INVITATIONS).createIndex(index("idx_coparent_invitation_email_status")
        .on("email", Sort.Direction.ASC).on("status", Sort.Direction.ASC));

    mongoTemplate.indexOps(ONBOARDING).createIndex(index("idx_coparent_onboarding_family")
        .on("familyId", Sort.Direction.ASC).unique());

    mongoTemplate.indexOps(EVENTS).createIndex(index("idx_coparent_event_family_active")
        .on("familyId", Sort.Direction.ASC).on("deletedAt", Sort.Direction.ASC));
    mongoTemplate.indexOps(EVENTS).createIndex(index("idx_coparent_event_family_start")
        .on("familyId", Sort.Direction.ASC).on("startDate", Sort.Direction.ASC)
        .on("deletedAt", Sort.Direction.ASC));
    mongoTemplate.indexOps(EVENTS).createIndex(index("idx_coparent_event_family_parent")
        .on("familyId", Sort.Direction.ASC).on("parentId", Sort.Direction.ASC)
        .on("deletedAt", Sort.Direction.ASC));

    mongoTemplate.indexOps(CATEGORIES).createIndex(index("idx_coparent_category_family_active")
        .on("familyId", Sort.Direction.ASC).on("deletedAt", Sort.Direction.ASC));

    mongoTemplate.indexOps(SCHEDULE_CHANGES).createIndex(
        index("idx_coparent_schedule_family_active")
            .on("familyId", Sort.Direction.ASC).on("deletedAt", Sort.Direction.ASC));
    mongoTemplate.indexOps(SCHEDULE_CHANGES).createIndex(
        index("idx_coparent_schedule_family_status")
            .on("familyId", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
            .on("deletedAt", Sort.Direction.ASC));
    mongoTemplate.indexOps(SCHEDULE_CHANGES).createIndex(
        index("idx_coparent_schedule_requester_status")
            .on("requestedBy", Sort.Direction.ASC).on("status", Sort.Direction.ASC));

    mongoTemplate.indexOps(CONVERSATIONS).createIndex(
        index("idx_coparent_conversation_family")
            .on("familyId", Sort.Direction.ASC));
    mongoTemplate.indexOps(CONVERSATIONS).createIndex(
        index("idx_coparent_conversation_family_recent")
            .on("familyId", Sort.Direction.ASC).on("lastMessageAt", Sort.Direction.DESC));
    mongoTemplate.indexOps(CONVERSATIONS).createIndex(
        index("idx_coparent_permission_id")
            .on("permissionRequest._id", Sort.Direction.ASC).sparse());

    mongoTemplate.indexOps(AUDITS).createIndex(index("idx_coparent_audit_actor")
        .on("performedBy", Sort.Direction.ASC));
    mongoTemplate.indexOps(AUDITS).createIndex(index("idx_coparent_audit_family_time")
        .on("familyId", Sort.Direction.ASC).on("timestamp", Sort.Direction.DESC));
    mongoTemplate.indexOps(AUDITS).createIndex(index("idx_coparent_audit_entity_time")
        .on("entityType", Sort.Direction.ASC).on("entityId", Sort.Direction.ASC)
        .on("timestamp", Sort.Direction.DESC));
  }

  private static Index index(final String name) {
    return new Index().named(name);
  }

  @RollbackExecution
  public void rollback() {
    // Collections contain private family data, so rollback is intentionally non-destructive.
    // A disabled feature flag is the safe application rollback; indexes are harmless in place.
  }
}
