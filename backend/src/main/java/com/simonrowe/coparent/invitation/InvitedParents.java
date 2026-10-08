package com.simonrowe.coparent.invitation;

import static com.simonrowe.coparent.shared.CoparentAccessPolicy.INVITED;
import static com.simonrowe.coparent.shared.CoparentAccessPolicy.UNINVITED;

import com.simonrowe.coparent.model.Parent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The parent row an invitation reserves for the person it invites, so a shared expense can
 * name them before they accept. Accepting turns this same row into their membership, which is
 * what lets those expenses become theirs without rewriting a single id.
 *
 * <p>The row is keyed on family and email: inviting the same address again reuses it, and so
 * keeps whatever was logged against it. It never matches a sign-in: its {@code auth0Id} is a
 * synthetic {@code invited:} value no Auth0 subject can take (they all contain {@code |}), and
 * every access check asks for an {@code active} parent anyway. The value exists because the
 * unique {@code (familyId, auth0Id)} index treats a missing subject as null, so two
 * placeholders in one family would otherwise collide.
 */
@Component
public class InvitedParents {

  static final String SUBJECT_PREFIX = "invited:";
  public static final int MAX_NAME = 100;

  private final MongoTemplate mongoTemplate;

  public InvitedParents(@Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /** Reserves, or re-reserves, the invited parent for this email. A blank name keeps theirs. */
  public void reserve(
      final ObjectId familyId,
      final String email,
      final String role,
      final String name,
      final Instant now) {
    final Query query = Query.query(Criteria.where("familyId").is(familyId)
        .and("email").is(email).and("status").in(INVITED, UNINVITED));
    final Update update = new Update()
        .set("status", INVITED).set("role", role).set("updatedAt", now)
        .setOnInsert("auth0Id", SUBJECT_PREFIX + new ObjectId().toHexString())
        .setOnInsert("createdAt", now);
    if (name == null || name.isBlank()) {
      update.setOnInsert("fullName", nameFromEmail(email));
    } else {
      update.set("fullName", name.strip());
    }
    try {
      mongoTemplate.upsert(query, update, Parent.class);
    } catch (DuplicateKeyException raced) {
      // A concurrent invite inserted the row first; the unique index made sure there is one.
      mongoTemplate.upsert(query, update, Parent.class);
    }
  }

  /** Marks the invited parent as no longer invited. Their expenses stay where they are. */
  public void retire(final ObjectId familyId, final String email, final Instant now) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("familyId").is(familyId)
            .and("email").is(email).and("status").is(INVITED)),
        new Update().set("status", UNINVITED).set("updatedAt", now),
        Parent.class);
  }

  /**
   * A readable first name from an address, for invitations that gave none: {@code
   * rhian.jones+school@example.com} becomes "Rhian Jones". Splitting on a possessive class
   * keeps it linear however the local part is shaped.
   */
  public static String nameFromEmail(final String email) {
    final String local = email == null ? "" : email.split("@", 2)[0].split("\\+", 2)[0];
    final List<String> words = new ArrayList<>();
    for (final String token : local.split("[._-]++")) {
      final String letters = token.replaceAll("[^\\p{L}]++", "");
      if (!letters.isEmpty()) {
        words.add(letters.substring(0, 1).toUpperCase(Locale.UK)
            + letters.substring(1).toLowerCase(Locale.UK));
      }
    }
    final String name = String.join(" ", words);
    if (name.isEmpty()) {
      return "Your co-parent";
    }
    return name.length() > MAX_NAME ? name.substring(0, MAX_NAME).strip() : name;
  }
}
