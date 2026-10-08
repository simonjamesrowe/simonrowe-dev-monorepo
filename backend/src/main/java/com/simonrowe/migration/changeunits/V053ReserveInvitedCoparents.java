package com.simonrowe.migration.changeunits;

import com.simonrowe.coparent.invitation.InvitedParents;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Lets a shared expense name a co-parent before they accept their invitation.
 *
 * <p>Creates the unique index that keeps one invited parent per family and email, which is
 * what makes "invite the same address again" find the row its expenses point at. Then it
 * reserves that parent for every invitation still open (pending, or expired and so resendable)
 * whose email is not already a member: invitations sent before this change reserved nothing.
 * It only adds rows, and {@link InvitedParents#reserve} matches on family and email, so a
 * second run changes nothing. {@code RestoreService} calls {@link #createIndexes} after a
 * restore; the rows themselves are in the backup.
 */
@ChangeUnit(id = "reserve-invited-coparents", order = "053", author = "simonrowe")
public class V053ReserveInvitedCoparents {

  private static final Logger log = LoggerFactory.getLogger(V053ReserveInvitedCoparents.class);

  @Execution
  public void execution(final CoparentMongoOperations operations) {
    final MongoTemplate template = operations.template();
    createIndexes(template);
    final InvitedParents invitedParents = new InvitedParents(template);
    final Instant now = Instant.now();
    final List<Invitation> open = template.find(
        Query.query(Criteria.where("status").in("pending", "expired")), Invitation.class);
    int reserved = 0;
    for (final Invitation invitation : open) {
      final boolean alreadyMember = template.exists(Query.query(Criteria
          .where("familyId").is(invitation.familyId())
          .and("email").regex("^" + Pattern.quote(invitation.email()) + "$", "i")
          .and("status").is(CoparentAccessPolicy.ACTIVE)), "parents");
      if (!alreadyMember) {
        invitedParents.reserve(invitation.familyId(), invitation.email(), invitation.role(),
            null, now);
        reserved++;
      }
    }
    log.info("Reserved {} invited CoParent parents for open invitations", reserved);
  }

  /** One invited (or uninvited) parent per family and email. Active parents are unaffected. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(V043CreateCoparentCollections.PARENTS).createIndex(new Index()
        .named("idx_coparent_parent_invited_email")
        .on("familyId", Sort.Direction.ASC)
        .on("email", Sort.Direction.ASC)
        .unique()
        .partial(PartialIndexFilter.of(Criteria.where("status")
            .in(CoparentAccessPolicy.INVITED, CoparentAccessPolicy.UNINVITED))));
  }

  @RollbackExecution
  public void rollback() {
    // Expenses may already name these parents; removing them would orphan the expenses.
  }
}
