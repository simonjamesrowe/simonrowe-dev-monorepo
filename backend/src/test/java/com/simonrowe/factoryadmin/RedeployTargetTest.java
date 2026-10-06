package com.simonrowe.factoryadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.platform.FactoryVersionClient;
import com.simonrowe.platform.MainCommit;
import com.simonrowe.platform.PlatformRelease;
import com.simonrowe.platform.PlatformReleaseRepository;
import com.simonrowe.platform.ReleaseSource;
import com.simonrowe.platform.RunningVersion;
import com.simonrowe.platform.ServiceVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Which commit a redeploy targets, now that the services are routinely built from different
 * commits. The property that matters: the target is the NEWEST build commit, because every
 * service's image at that commit is the one running, and any older commit would roll a service
 * back.
 */
class RedeployTargetTest {

  private static final String BACKEND = "1111111111111111111111111111111111111111";
  private static final String FRONTEND = "2222222222222222222222222222222222222222";
  private static final String FACTORY = "3333333333333333333333333333333333333333";

  private static final Instant OLDEST = Instant.parse("2026-10-01T09:00:00Z");
  private static final Instant MIDDLE = Instant.parse("2026-10-02T09:00:00Z");
  private static final Instant NEWEST = Instant.parse("2026-10-03T09:00:00Z");

  private final FactoryVersionClient factoryVersions = mock(FactoryVersionClient.class);
  private final PlatformReleaseRepository releases = mock(PlatformReleaseRepository.class);

  @Test
  void targetsTheFrontendWhenOnlyTheFrontendWasRebuiltLast() {
    factoryBuiltAt(FACTORY, OLDEST);
    RedeployTarget target = backendBuiltAt(MIDDLE);
    recorded(FRONTEND, NEWEST);

    assertThat(target.resolve(FRONTEND).commit()).isEqualTo(FRONTEND);
  }

  @Test
  void targetsTheFactoryWhenItWasBuiltMostRecently() {
    // Targeting the backend's older commit here would pull the software-factory image built
    // BEFORE the running one - a rollback dressed as a redeploy.
    factoryBuiltAt(FACTORY, NEWEST);
    RedeployTarget target = backendBuiltAt(MIDDLE);
    recorded(FRONTEND, OLDEST);

    assertThat(target.resolve(FRONTEND).commit()).isEqualTo(FACTORY);
  }

  @Test
  void targetsTheSharedCommitWhenEverythingWasBuiltTogether() {
    factoryBuiltAt(BACKEND, MIDDLE);
    RedeployTarget target = backendBuiltAt(MIDDLE);
    recorded(BACKEND, MIDDLE);

    assertThat(target.resolve(BACKEND).commit()).isEqualTo(BACKEND);
  }

  @Test
  void datesTheFrontendFromTheRecordedHistoryNotFromTheBrowser() {
    factoryBuiltAt(FACTORY, OLDEST);
    RedeployTarget target = backendBuiltAt(MIDDLE);
    when(releases.findById(FRONTEND)).thenReturn(Optional.empty());

    assertPrecondition(() -> target.resolve(FRONTEND), "not in the release history");
  }

  @Test
  void refusesFrontendCommitThatIsNotFullSha() {
    factoryBuiltAt(FACTORY, OLDEST);
    RedeployTarget target = backendBuiltAt(MIDDLE);

    assertPrecondition(() -> target.resolve("unknown"), "full commit SHA");
    assertPrecondition(() -> target.resolve(null), "full commit SHA");
  }

  @Test
  void refusesWhenTheBackendDoesNotKnowItsCommit() {
    RedeployTarget target = new RedeployTarget(
        new RunningVersion(null), factoryVersions, releases);
    factoryBuiltAt(FACTORY, OLDEST);
    recorded(FRONTEND, NEWEST);

    assertPrecondition(() -> target.resolve(FRONTEND), "backend");
  }

  @Test
  void refusesWhenTheFactoryIsNotReporting() {
    // Its image is deployed too, so redeploying without knowing what it runs could roll it back.
    RedeployTarget target = backendBuiltAt(MIDDLE);
    when(factoryVersions.versions()).thenReturn(List.of(
        ServiceVersion.unreachable("software-factory"), ServiceVersion.unreachable("deployer")));
    recorded(FRONTEND, NEWEST);

    assertPrecondition(() -> target.resolve(FRONTEND), "software-factory");
  }

  @Test
  void reportsOnlyTheServerSideCommitsItKnows() {
    RedeployTarget target = backendBuiltAt(MIDDLE);
    when(factoryVersions.versions()).thenReturn(List.of(
        ServiceVersion.unreachable("software-factory"), ServiceVersion.unreachable("deployer")));

    assertThat(target.serverSideCommits())
        .containsExactly(new ServiceCommit("backend", BACKEND, MIDDLE));
  }

  private RedeployTarget backendBuiltAt(final Instant backendTime) {
    Properties properties = new Properties();
    properties.setProperty("commit", BACKEND);
    properties.setProperty("commitTime", Long.toString(backendTime.getEpochSecond()));
    return new RedeployTarget(
        new RunningVersion(new BuildProperties(properties)), factoryVersions, releases);
  }

  private void factoryBuiltAt(final String commit, final Instant time) {
    when(factoryVersions.versions()).thenReturn(List.of(
        new ServiceVersion("software-factory", commit, commit.substring(0, 7), "subject", time,
            time, true),
        new ServiceVersion("deployer", commit, commit.substring(0, 7), "subject", time, time,
            true)));
  }

  private void recorded(final String sha, final Instant time) {
    when(releases.findById(sha)).thenReturn(Optional.of(PlatformRelease.fromCommit(
        new MainCommit(sha, time, "feat: something", "", List.of()),
        ReleaseSource.PUBLISHED_HISTORY,
        time)));
  }

  private static void assertPrecondition(final ThrowingCallable call, final String message) {
    assertThatThrownBy(call)
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining(message)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);
  }
}
