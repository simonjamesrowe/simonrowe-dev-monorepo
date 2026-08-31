package com.simonrowe.factory.cvefix.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Testcontainers
class CveFixRepositoriesTest {

  @Container
  private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

  @DynamicPropertySource
  static void mongoUri(final DynamicPropertyRegistry registry) {
    registry.add(
        "spring.mongodb.uri",
        () -> MONGO.getConnectionString() + "/software_factory_test");
  }

  @Autowired private CveFixRunRepository runs;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void roundTripsRunRecord() {
    CveFixRunRecord saved =
        runs.save(
            new CveFixRunRecord(
                CveFixRunRecord.idFor("cve-fix-wf-1"), "cve-fix-wf-1",
                Instant.parse("2026-08-11T00:00:00Z"),
                CveFixStatus.COMPLETED, 7, List.of("bar 1.0 -> 1.1"),
                "https://github.com/o/r/pull/1", 2, null));

    assertThat(runs.findById("cve-fix-wf-1")).contains(saved);
  }

  private static final EnumSet<CveFixStatus> OBSERVED =
      EnumSet.of(CveFixStatus.COMPLETED, CveFixStatus.NO_FINDINGS);

  @Test
  void findsTheMostRecentRunExcludingTheOneNamed() {
    runs.deleteAll();
    runs.save(record("older", Instant.parse("2026-08-01T00:00:00Z"), CveFixStatus.COMPLETED, 3));
    runs.save(record("newer", Instant.parse("2026-08-02T00:00:00Z"), CveFixStatus.NO_FINDINGS, 0));
    runs.save(
        record("current", Instant.parse("2026-08-03T00:00:00Z"), CveFixStatus.NO_FINDINGS, 0));

    assertThat(runs.findFirstByIdNotAndStatusInOrderByStartedAtDesc("current", OBSERVED))
        .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo("newer"));
    assertThat(runs.findFirstByIdNotAndStatusInOrderByStartedAtDesc("newer", OBSERVED))
        .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo("current"));
  }

  @Test
  void findsNothingWhenTheOnlyRunIsTheOneExcluded() {
    runs.deleteAll();
    runs.save(record("only", Instant.parse("2026-08-01T00:00:00Z"), CveFixStatus.COMPLETED, 1));

    assertThat(runs.findFirstByIdNotAndStatusInOrderByStartedAtDesc("only", OBSERVED)).isEmpty();
  }

  @Test
  void skipsFailedRunNewerThanTheLastRunThatObservedDependencyTrack() {
    // Regression test: a FAILED row (Dependency-Track down, findingsSeen == 0) must not be
    // mistaken for a clean previous scan, or the dirty-to-clean transition comment is lost for
    // every night thereafter.
    runs.deleteAll();
    runs.save(record("dirty", Instant.parse("2026-08-01T00:00:00Z"), CveFixStatus.COMPLETED, 61));
    runs.save(record("failed", Instant.parse("2026-08-02T00:00:00Z"), CveFixStatus.FAILED, 0));

    assertThat(runs.findFirstByIdNotAndStatusInOrderByStartedAtDesc("current", OBSERVED))
        .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo("dirty"));
  }

  private static CveFixRunRecord record(
      final String id, final Instant startedAt, final CveFixStatus status,
      final int findingsSeen) {
    return new CveFixRunRecord(
        id, id, startedAt, status, findingsSeen, List.of(), null, 0, "detail");
  }

  @Test
  void indexInitializerCreatesTheRunHistoryIndex() {
    // CveFixIndexInitializer is an ApplicationRunner and is gated on the feature flag, so it
    // does not run inside this slice. Drive it directly — that also proves it is idempotent,
    // which matters because it runs on every restart.
    CveFixIndexInitializer initializer = new CveFixIndexInitializer(mongoTemplate);
    initializer.run(null);
    initializer.run(null);

    assertThat(mongoTemplate.indexOps(CveFixRunRecord.class).getIndexInfo())
        .anySatisfy(
            index -> {
              assertThat(index.getName()).isEqualTo("startedAt");
            });
  }
}
