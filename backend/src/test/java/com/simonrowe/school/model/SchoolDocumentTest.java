package com.simonrowe.school.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchoolDocumentTest {

  private SchoolDocument document(final Visibility visibility) {
    return new SchoolDocument("id", SchoolSourceType.EMAIL, "ref", "Title", "Body",
        Instant.EPOCH, Instant.EPOCH, visibility, null, null, null, null, false,
        List.of(), "hash", null);
  }

  @Test
  @DisplayName("a null visibility becomes RESTRICTED, never PUBLIC")
  void nullVisibilityFailsClosed() {
    assertThat(document(null).visibility()).isEqualTo(Visibility.RESTRICTED);
  }

  @Test
  @DisplayName("a classifier proposal cannot change the effective visibility")
  void proposalDoesNotPromote() {
    final SchoolDocument proposed = document(Visibility.RESTRICTED)
        .withProposal(Visibility.PUBLIC, "looks like a whole-school newsletter");

    assertThat(proposed.proposedVisibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(proposed.visibility()).isEqualTo(Visibility.RESTRICTED);
    assertThat(proposed.needsDecision()).isTrue();
  }

  @Test
  @DisplayName("approval promotes to public and records who did it")
  void approvalPromotes() {
    final SchoolDocument approved = document(Visibility.RESTRICTED)
        .withProposal(Visibility.PUBLIC, "reason")
        .withApproval("simon", Instant.EPOCH, false);

    assertThat(approved.visibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(approved.approvedBy()).isEqualTo("simon");
  }

  @Test
  @DisplayName("revoking approval returns a document to restricted")
  void revokeDemotes() {
    final SchoolDocument revoked = document(Visibility.PUBLIC)
        .withApproval("simon", Instant.EPOCH, false)
        .withApprovalRevoked();

    assertThat(revoked.visibility()).isEqualTo(Visibility.RESTRICTED);
    assertThat(revoked.approvedBy()).isNull();
  }

  @Test
  @DisplayName("a restricted proposal still needs a decision — this is what emptied the queue")
  void restrictedProposalStillNeedsDecision() {
    // The queue used to require proposedVisibility != RESTRICTED. The classifier proposes
    // RESTRICTED for very nearly everything, so Approvals was permanently empty while the
    // documents list was full of restricted items nobody could act on.
    final SchoolDocument proposed = document(Visibility.RESTRICTED)
        .withProposal(Visibility.RESTRICTED, "classifier proposed RESTRICTED");

    assertThat(proposed.needsDecision()).isTrue();
  }

  @Test
  @DisplayName("declining takes a document out of the queue for good")
  void decliningEndsTheDecision() {
    final SchoolDocument declined = document(Visibility.RESTRICTED)
        .withProposal(Visibility.RESTRICTED, "classifier proposed RESTRICTED")
        .withDeclined(Instant.EPOCH);

    assertThat(declined.needsDecision()).isFalse();
    assertThat(declined.declinedAt()).isEqualTo(Instant.EPOCH);
    assertThat(declined.visibility()).isEqualTo(Visibility.RESTRICTED);
  }

  @Test
  @DisplayName("approving takes a document out of the queue too")
  void approvingEndsTheDecision() {
    final SchoolDocument approved = document(Visibility.RESTRICTED)
        .withApproval("simon", Instant.EPOCH, true);

    assertThat(approved.needsDecision()).isFalse();
  }

  @Test
  @DisplayName("revoking an approval puts it back in the queue, because nobody has said no")
  void revokingReturnsItToTheQueue() {
    final SchoolDocument revoked = document(Visibility.RESTRICTED)
        .withApproval("simon", Instant.EPOCH, true)
        .withApprovalRevoked();

    assertThat(revoked.needsDecision()).isTrue();
  }
}
