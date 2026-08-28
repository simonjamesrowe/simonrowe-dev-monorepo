package com.simonrowe.factory.cvefix.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
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
        "spring.data.mongodb.uri",
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
