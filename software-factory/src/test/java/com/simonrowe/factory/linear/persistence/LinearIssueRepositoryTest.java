package com.simonrowe.factory.linear.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueStateType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Testcontainers
class LinearIssueRepositoryTest {

  @Container
  private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

  @DynamicPropertySource
  static void mongoUri(final DynamicPropertyRegistry registry) {
    registry.add(
        "spring.mongodb.uri", () -> MONGO.getConnectionString() + "/software_factory_test");
  }

  @Autowired private LinearIssueRepository records;

  // The container is a static singleton shared across this class, so the collection has to be
  // cleared per test or the ordering assertion sees rows from whichever test ran first.
  @BeforeEach
  void clearCollection() {
    records.deleteAll();
  }

  @Test
  void roundTripsEveryFieldIncludingTheEnumAndTheDecisionLog() {
    LinearIssueRecord saved =
        records.save(
            LinearIssueRecord.first("fp1", "deploy", List.of("recreate", "backend"), Instant.EPOCH)
                .withPendingAttachment("uuid", "SIM-1", "https://linear.app/i/1")
                .withAttachmentWritten()
                .withDecision(
                    new LinearIssueDecision(
                        Instant.EPOCH, FilingDecision.FILED_NEW, "run-1", "deploy-prod", "boom",
                        false),
                    Instant.EPOCH,
                    IssueStateType.TRIAGE));

    LinearIssueRecord loaded = records.findById("fp1").orElseThrow();
    assertThat(loaded.producer()).isEqualTo("deploy");
    assertThat(loaded.fingerprintVersion()).isEqualTo("v1");
    assertThat(loaded.keyParts()).containsExactly("recreate", "backend");
    assertThat(loaded.issueIdentifier()).isEqualTo("SIM-1");
    assertThat(loaded.attachmentPending()).isFalse();
    assertThat(loaded.lastKnownStateType()).isEqualTo(IssueStateType.TRIAGE);
    assertThat(loaded.decisions()).hasSize(1);
    assertThat(loaded.decisions().get(0).decision()).isEqualTo(FilingDecision.FILED_NEW);
    assertThat(loaded.decisions().get(0).occurrenceId()).isEqualTo("run-1");
    assertThat(saved.occurrences()).isEqualTo(1);
  }

  @Test
  void listsProducerRecordsNewestOccurrenceFirst() {
    records.save(
        LinearIssueRecord.first("old", "deploy", List.of("a"), Instant.parse(
            "2026-01-01T00:00:00Z")));
    records.save(
        LinearIssueRecord.first("new", "deploy", List.of("b"), Instant.parse(
            "2026-06-01T00:00:00Z")));
    records.save(
        LinearIssueRecord.first("other", "cvefix", List.of("c"), Instant.parse(
            "2026-07-01T00:00:00Z")));

    assertThat(records.findByProducerOrderByLastSeenAtDesc("deploy"))
        .extracting(LinearIssueRecord::id)
        .containsExactly("new", "old");
  }
}
