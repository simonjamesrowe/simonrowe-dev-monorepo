package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.mongodb.core.MongoTemplate;

class ReleaseRecorderTest extends AbstractIntegrationTest {

  private static final String RUNNING_SHA = "840c311abcdef0123456789abcdef0123456789a";
  private static final String OLDER_SHA = "39e0f7aabcdef0123456789abcdef0123456789a";
  private static final String NEWEST_SHA = "aaaaaaaabcdef0123456789abcdef0123456789a";

  @Autowired
  private PlatformReleaseRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void clearCollection() {
    mongoTemplate.dropCollection(PlatformRelease.class);
  }

  private static MainCommit commit(final String sha, final String subject, final long epoch) {
    return new MainCommit(sha, Instant.ofEpochSecond(epoch), subject, "body text", List.of());
  }

  private static RunningVersion running() {
    Properties properties = new Properties();
    properties.put("commit", RUNNING_SHA);
    properties.put("commitTime", "1756200000");
    properties.put("commitSubject", "docs: overhaul the README");
    return new RunningVersion(new BuildProperties(properties));
  }

  private ReleaseRecorder recorder(final FakeHistory history, final int maxFileLookups) {
    return new ReleaseRecorder(running(), history, repository, true, maxFileLookups);
  }

  private static FakeHistory history(final MainCommit... commits) {
    return new FakeHistory(List.of(commits));
  }

  @Test
  void storesEveryListedCommitWithItsFiles() {
    int inserted = recorder(history(
        commit(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        commit(OLDER_SHA, "feat: deploy automatically", 1756100000L)), 20).record();

    assertThat(inserted).isEqualTo(2);
    assertThat(repository.findById(OLDER_SHA).orElseThrow().getFilesChanged())
        .containsExactly("files/of/" + OLDER_SHA);
  }

  @Test
  void marksTheRunningReleaseAsRunningAndTheRestAsPublished() {
    recorder(history(
        commit(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        commit(OLDER_SHA, "feat: deploy automatically", 1756100000L)), 20).record();

    assertThat(repository.findById(RUNNING_SHA).orElseThrow().getSource())
        .isEqualTo(ReleaseSource.RUNNING);
    assertThat(repository.findById(OLDER_SHA).orElseThrow().getSource())
        .isEqualTo(ReleaseSource.PUBLISHED_HISTORY);
  }

  @Test
  void looksUpFilesOnlyForCommitsItDoesNotAlreadyHold() {
    // Files cost one GitHub request per commit against an anonymous limit of 60 an hour, so a
    // poll over an unchanged branch must cost none.
    FakeHistory history = history(
        commit(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        commit(OLDER_SHA, "feat: deploy automatically", 1756100000L));
    ReleaseRecorder recorder = recorder(history, 20);
    recorder.record();
    history.lookups.clear();

    assertThat(recorder.record()).isZero();
    assertThat(history.lookups).isEmpty();
    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void spendsAtMostTheConfiguredLookupsPerPollNewestFirst() {
    FakeHistory history = history(
        commit(NEWEST_SHA, "feat: newest", 1756300000L),
        commit(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        commit(OLDER_SHA, "feat: deploy automatically", 1756100000L));

    assertThat(recorder(history, 2).record()).isEqualTo(2);
    assertThat(history.lookups).containsExactly(NEWEST_SHA, RUNNING_SHA);
    assertThat(repository.existsById(OLDER_SHA)).isFalse();

    // The next poll picks up where the cap stopped this one.
    assertThat(recorder(history, 2).record()).isEqualTo(1);
    assertThat(repository.existsById(OLDER_SHA)).isTrue();
  }

  @Test
  void storesNothingForCommitWhoseFilesCouldNotBeRead() {
    // A record is never revisited once stored, so storing it without files would leave the
    // release note written from a subject alone for ever.
    FakeHistory history = history(
        commit(NEWEST_SHA, "feat: newest", 1756300000L),
        commit(OLDER_SHA, "feat: deploy automatically", 1756100000L));
    history.failFilesFor = OLDER_SHA;

    assertThatThrownBy(() -> recorder(history, 20).record())
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.existsById(NEWEST_SHA)).isTrue();
    assertThat(repository.existsById(OLDER_SHA)).isFalse();
  }

  @Test
  void neverOverwritesAnExistingSummary() {
    ReleaseRecorder recorder =
        recorder(history(commit(RUNNING_SHA, "docs: overhaul the README", 1756200000L)), 20);
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
  void promotesPublishedRecordToRunningWhenThisBuildIsRunningOnIt() {
    // The commit is normally recorded from GitHub before the deploy that runs it, so it is
    // already present as PUBLISHED_HISTORY. Running on it is the evidence that upgrades the
    // claim, and it must not cost the summary.
    PlatformRelease published = PlatformRelease.fromCommit(
        commit(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        ReleaseSource.PUBLISHED_HISTORY,
        Instant.ofEpochSecond(1756190000L));
    published.setSummary("Already written.");
    published.setSummaryStatus(ReleaseSummaryStatus.READY);
    repository.save(published);

    recorder(history(commit(RUNNING_SHA, "docs: overhaul the README", 1756200000L)), 20)
        .record();

    PlatformRelease after = repository.findById(RUNNING_SHA).orElseThrow();
    assertThat(after.getSource()).isEqualTo(ReleaseSource.RUNNING);
    assertThat(after.getSummary()).isEqualTo("Already written.");
  }

  @Test
  void doesNothingWhenSwitchedOff() {
    FakeHistory history = history(commit(RUNNING_SHA, "docs: overhaul the README", 1756200000L));

    new ReleaseRecorder(running(), history, repository, false, 20).scheduledPoll();

    assertThat(repository.count()).isZero();
    assertThat(history.listings).isZero();
  }

  @Test
  void survivesAnUnreadableHistory() {
    FakeHistory history = history();
    history.failListing = true;

    recorder(history, 20).scheduledPoll();

    assertThat(repository.count()).isZero();
  }

  /** A {@link CommitHistory} that records what it was asked for. */
  private static final class FakeHistory implements CommitHistory {

    private final List<MainCommit> commits;
    private final List<String> lookups = new ArrayList<>();
    private int listings;
    private boolean failListing;
    private String failFilesFor;

    private FakeHistory(final List<MainCommit> commits) {
      this.commits = commits;
    }

    @Override
    public List<MainCommit> recent() {
      listings++;
      if (failListing) {
        throw new IllegalStateException("GitHub is unreachable");
      }
      return commits;
    }

    @Override
    public List<String> filesChanged(final String sha) {
      if (sha.equals(failFilesFor)) {
        throw new IllegalStateException("rate limited");
      }
      lookups.add(sha);
      return List.of("files/of/" + sha);
    }
  }
}
