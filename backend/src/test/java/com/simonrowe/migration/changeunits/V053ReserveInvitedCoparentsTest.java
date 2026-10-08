package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Invitations sent before invited parents existed get one, and only the ones still open. */
class V053ReserveInvitedCoparentsTest extends AbstractIntegrationTest {

  private static final ObjectId FAMILY = new ObjectId();

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  private final V053ReserveInvitedCoparents changeUnit = new V053ReserveInvitedCoparents();

  @BeforeEach
  @AfterEach
  void clean() {
    List.of(V043CreateCoparentCollections.PARENTS, V043CreateCoparentCollections.INVITATIONS)
        .forEach(name -> mongoTemplate.getCollection(name).deleteMany(new Document()));
  }

  @Test
  void reservesParentsForOpenInvitationsWhoseEmailIsNotMember() {
    invitation("rhian@example.com", "pending");
    invitation("old@example.com", "expired");
    invitation("done@example.com", "accepted");
    invitation("gone@example.com", "canceled");
    invitation("member@example.com", "pending");
    mongoTemplate.save(new Parent(null, "auth0|member", FAMILY, "Member", "Member@Example.com",
        "co-parent", "active", null, null, null, Instant.now(), Instant.now()));

    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));

    assertThat(reserved()).extracting(Parent::email)
        .containsExactlyInAnyOrder("rhian@example.com", "old@example.com");
    assertThat(reserved()).allSatisfy(parent -> {
      assertThat(parent.status()).isEqualTo("invited");
      assertThat(parent.auth0Id()).startsWith("invited:");
      assertThat(parent.familyId()).isEqualTo(FAMILY);
    });
    assertThat(reserved()).filteredOn(parent -> "rhian@example.com".equals(parent.email()))
        .singleElement().extracting(Parent::fullName).isEqualTo("Rhian");
  }

  @Test
  void secondRunAddsNothing() {
    invitation("rhian@example.com", "pending");
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));
    final List<ObjectId> first = reserved().stream().map(Parent::id).toList();

    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));

    assertThat(reserved()).extracting(Parent::id).containsExactlyElementsOf(first);
  }

  @Test
  void theIndexAllowsOneInvitedParentPerFamilyAndEmailAndIgnoresActiveOnes() {
    V053ReserveInvitedCoparents.createIndexes(mongoTemplate);
    final Instant now = Instant.now();
    mongoTemplate.insert(placeholder("invited", now));
    assertThatThrownBy(() -> mongoTemplate.insert(placeholder("uninvited", now)))
        .isInstanceOf(DuplicateKeyException.class);

    // Active members are outside the index, so two families' members never collide on it.
    mongoTemplate.insert(new Parent(null, "auth0|a", FAMILY, "A", "rhian@example.com",
        "co-parent", "active", null, null, null, now, now));
  }

  private Parent placeholder(final String status, final Instant now) {
    return new Parent(null, "invited:" + new ObjectId().toHexString(), FAMILY, "Rhian",
        "rhian@example.com", "co-parent", status, null, null, null, now, now);
  }

  private List<Parent> reserved() {
    return mongoTemplate.find(Query.query(Criteria.where("status").in("invited", "uninvited")),
        Parent.class);
  }

  private void invitation(final String email, final String status) {
    final Instant now = Instant.now();
    mongoTemplate.save(new Invitation(null, FAMILY, email, "co-parent", status,
        new ObjectId().toHexString(), now, now.plusSeconds(3600), null, null, null, null,
        now, now));
  }
}
