package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.mongodb.core.MongoTemplate;

class ReleaseRecorderTest extends AbstractIntegrationTest {

  private static final String RUNNING_SHA = "840c311abcdef0123456789abcdef0123456789a";
  private static final String OLDER_SHA = "39e0f7aabcdef0123456789abcdef0123456789a";

  @Autowired
  private PlatformReleaseRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void clearCollection() {
    mongoTemplate.dropCollection(PlatformRelease.class);
  }

  private static BakedRelease baked(final String sha, final String subject, final long epoch) {
    return new BakedRelease(
        sha, Instant.ofEpochSecond(epoch), subject, "body text", List.of("a.java"));
  }

  private ReleaseRecorder recorder(final List<BakedRelease> history) {
    java.util.Properties properties = new java.util.Properties();
    properties.put("commit", RUNNING_SHA);
    properties.put("commitTime", "1756200000");
    properties.put("commitSubject", "docs: overhaul the README");
    return new ReleaseRecorder(
        new RunningVersion(new BuildProperties(properties)), () -> history, repository);
  }

  @Test
  void seedsEveryBakedRelease() {
    int inserted = recorder(List.of(
        baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        baked(OLDER_SHA, "feat: deploy automatically", 1756100000L))).record();

    assertThat(inserted).isEqualTo(2);
    assertThat(repository.findAll()).extracting(PlatformRelease::getId)
        .containsExactlyInAnyOrder(RUNNING_SHA, OLDER_SHA);
  }

  @Test
  void marksTheRunningReleaseAsRunningAndTheRestAsPublished() {
    recorder(List.of(
        baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        baked(OLDER_SHA, "feat: deploy automatically", 1756100000L))).record();

    assertThat(repository.findById(RUNNING_SHA).orElseThrow().getSource())
        .isEqualTo(ReleaseSource.RUNNING);
    assertThat(repository.findById(OLDER_SHA).orElseThrow().getSource())
        .isEqualTo(ReleaseSource.PUBLISHED_HISTORY);
  }

  @Test
  void insertsNothingOnSecondRun() {
    ReleaseRecorder recorder = recorder(List.of(
        baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        baked(OLDER_SHA, "feat: deploy automatically", 1756100000L)));
    recorder.record();

    assertThat(recorder.record()).isZero();
    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void neverOverwritesAnExistingSummary() {
    ReleaseRecorder recorder =
        recorder(List.of(baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L)));
    recorder.record();
    PlatformRelease stored = repository.findById(RUNNING_SHA).orElseThrow();
    stored.setSummary("An expensive paragraph.");
    stored.setSummaryStatus(ReleaseSummaryStatus.READY);
    repository.save(stored);

    recorder.record();

    PlatformRelease after = repository.findById(RUNNING_SHA).orElseThrow();
    assertThat(after.getSummary()).isEqualTo("An expensive paragraph.");
    assertThat(after.getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.READY);
  }

  @Test
  void promotesPublishedRecordToRunningWhenThisBuildBootsOnIt() {
    // The history is baked before the deploy, so the running SHA is usually already
    // present as PUBLISHED_HISTORY from an earlier boot. Booting on it is the evidence
    // that upgrades the claim, and it must not cost the summary.
    PlatformRelease published = PlatformRelease.fromBaked(
        baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        ReleaseSource.PUBLISHED_HISTORY,
        Instant.ofEpochSecond(1756190000L));
    published.setSummary("Already written.");
    published.setSummaryStatus(ReleaseSummaryStatus.READY);
    repository.save(published);

    ReleaseRecorder seeder =
        recorder(List.of(baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L)));
    seeder.record();

    PlatformRelease after = repository.findById(RUNNING_SHA).orElseThrow();
    assertThat(after.getSource()).isEqualTo(ReleaseSource.RUNNING);
    assertThat(after.getSummary()).isEqualTo("Already written.");
  }

  @Test
  void recordsNothingWhenThereIsNoBuildInfo() {
    ReleaseRecorder devBuild =
        new ReleaseRecorder(new RunningVersion(null), List::of, repository);

    assertThat(devBuild.record()).isZero();
    assertThat(repository.count()).isZero();
  }
}
