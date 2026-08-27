package com.simonrowe.factory.deploy.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.deploy.domain.DeployPhase;
import com.simonrowe.factory.deploy.domain.DeployStatus;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.SyncDecision;
import com.simonrowe.factory.deploy.domain.SyncOutcome;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Testcontainers
class DeployRunRepositoryTest {

  @Container
  private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

  @DynamicPropertySource
  static void mongoUri(final DynamicPropertyRegistry registry) {
    registry.add(
        "spring.data.mongodb.uri", () -> MONGO.getConnectionString() + "/software_factory_test");
  }

  @Autowired private DeployRunRepository runs;

  // The container is a static singleton shared across every test in this class, so the
  // collection has to be cleared per test - otherwise the ordering assertions below see
  // rows left by whichever test happened to run first.
  @BeforeEach
  void clearCollection() {
    runs.deleteAll();
  }

  private static DeployRunRecord record(final String runId, final String sha, final Instant at) {
    return new DeployRunRecord(
        DeployRunRecord.idFor(runId),
        "deploy-prod",
        sha,
        "workflow_run",
        at,
        at.plusSeconds(300),
        DeployStatus.DEPLOYED,
        List.of(new PhaseOutcome(DeployPhase.PULL, true, 0, "pulled", 1000L)),
        new SyncOutcome(
            SyncDecision.APPLIED, "old-sha", sha, List.of("backend"), List.of(), null, null, "ok"),
        false,
        null,
        false,
        null,
        null,
        "deployed",
        false);
  }

  @Test
  void roundTripsOneRun() {
    DeployRunRecord saved =
        runs.save(record("run-1", "sha-1", Instant.parse("2026-08-26T10:00:00Z")));

    assertThat(runs.findById("run-1")).contains(saved);
  }

  @Test
  void twoDeploysCoexistBecauseTheKeyIsTheRunIdNotTheWorkflowId() {
    // The regression this guards. The workflow id is the fixed constant `deploy-prod`, which is
    // what makes deploys coalesce — so keying the document on it (as CveFixRunRecord does) would
    // make the second deploy silently overwrite the first, and deploy history would be one row
    // forever.
    runs.save(record("run-1", "sha-1", Instant.parse("2026-08-26T10:00:00Z")));
    runs.save(record("run-2", "sha-2", Instant.parse("2026-08-26T11:00:00Z")));

    assertThat(runs.findAll()).hasSize(2);
    assertThat(runs.findAll()).allMatch(run -> "deploy-prod".equals(run.workflowId()));
  }

  @Test
  void reDrivingTheSameRunOverwritesItsOwnRow() {
    runs.save(record("run-1", "sha-1", Instant.parse("2026-08-26T10:00:00Z")));
    runs.save(record("run-1", "sha-1", Instant.parse("2026-08-26T10:00:00Z")));

    assertThat(runs.findAll()).hasSize(1);
  }

  @Test
  void readsRecentDeploysNewestFirst() {
    runs.save(record("run-old", "sha-old", Instant.parse("2026-08-01T10:00:00Z")));
    runs.save(record("run-new", "sha-new", Instant.parse("2026-08-26T10:00:00Z")));
    runs.save(record("run-mid", "sha-mid", Instant.parse("2026-08-14T10:00:00Z")));

    assertThat(runs.findTop20ByOrderByStartedAtDesc())
        .extracting(DeployRunRecord::sha)
        .containsExactly("sha-new", "sha-mid", "sha-old");
  }

  @Test
  void phaseDetailIsBoundedSoTheRecordNeverBecomesLogStore() {
    String huge = "x".repeat(PhaseOutcome.MAX_DETAIL + 500);
    PhaseOutcome outcome = new PhaseOutcome(DeployPhase.VERIFY, false, 1, huge, 5L);

    assertThat(outcome.detail()).hasSize(PhaseOutcome.MAX_DETAIL);
  }
}
