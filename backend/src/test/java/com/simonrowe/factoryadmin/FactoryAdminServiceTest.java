package com.simonrowe.factoryadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factoryadmin.FactoryInstanceStatus.ModuleStatus;
import com.simonrowe.platform.RunningVersion;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

/**
 * The trusted boundary. Every guard the console shows is repeated here, because the console is
 * not the boundary — a request that skipped it has to fail for the same reasons.
 */
class FactoryAdminServiceTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final String CONFIRMATION = "REDEPLOY 0123456";

  private final FactoryAdminClient client = mock(FactoryAdminClient.class);

  @Test
  void reportsAllSixModulesEvenWhenNeitherContainerAnswers() {
    when(client.factoryStatus()).thenThrow(new ResourceAccessException("down"));
    when(client.deployerStatus()).thenThrow(new ResourceAccessException("down"));

    FactoryAdminStatus status = service(SHA).status();

    assertThat(status.factoryReachable()).isFalse();
    assertThat(status.deployerReachable()).isFalse();
    assertThat(status.modules()).hasSize(6);
    assertThat(status.modules()).allMatch(module -> !module.ready());
    assertThat(status.modules()).allMatch(
        module -> "Owning factory service is unreachable".equals(module.diagnostic()));
  }

  @Test
  void keepsTheFactoryModulesTruthfulWhenOnlyTheDeployerIsDown() {
    // A partial failure must not hide the modules that are healthy.
    when(client.factoryStatus()).thenReturn(instance("software-factory", ready("feedback")));
    when(client.deployerStatus()).thenThrow(new ResourceAccessException("down"));

    FactoryAdminStatus status = service(SHA).status();

    assertThat(status.factoryReachable()).isTrue();
    assertThat(module(status, "feedback").ready()).isTrue();
    assertThat(module(status, "deploy").diagnostic())
        .isEqualTo("Owning factory service is unreachable");
  }

  @Test
  void takesDeployAndBackupFromTheDeployerRatherThanTheFactory() {
    // Both containers run the same image, so software-factory reports these two modules from its
    // own configuration — where they are switched off. Only the deployer's answer is meaningful.
    when(client.factoryStatus())
        .thenReturn(instance("software-factory", disabled("deploy"), disabled("platformbackup")));
    when(client.deployerStatus())
        .thenReturn(instance("deployer", ready("deploy"), ready("platformbackup")));

    FactoryAdminStatus status = service(SHA).status();

    assertThat(module(status, "deploy").ready()).isTrue();
    assertThat(module(status, "platformbackup").ready()).isTrue();
  }

  @Test
  void refusesAnActionWhoseModuleIsNotReady() {
    // Starting a workflow on a queue nothing polls does not fail — it sits in Temporal looking
    // accepted until an activity timeout, which is the worst of both outcomes.
    when(client.factoryStatus()).thenReturn(instance("software-factory", disabled("cvefix")));
    when(client.deployerStatus()).thenReturn(instance("deployer"));

    assertThatThrownBy(() -> service(SHA).startVulnerabilityScan())
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);

    verify(client, never()).startVulnerabilityScan();
  }

  @Test
  void startsAnActionWhoseModuleIsReady() {
    when(client.factoryStatus()).thenReturn(instance("software-factory", ready("cvefix")));
    when(client.deployerStatus()).thenReturn(instance("deployer"));
    when(client.startVulnerabilityScan())
        .thenReturn(new FactoryRunAccepted("cve-scan-manual-1", "run-1", "accepted"));

    assertThat(service(SHA).startVulnerabilityScan().workflowId())
        .isEqualTo("cve-scan-manual-1");
  }

  @Test
  void rejectsNonPositivePullNumberBeforeCallingTheFactory() {
    assertThatThrownBy(() -> service(SHA).startFeedback(0))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

    verify(client, never()).startFeedback(anyString(), anyString(), anyInt());
  }

  @Test
  void refusesDeployWhenTheTwoServicesDisagreeOnTheRunningCommit() {
    assertThatThrownBy(() -> service(SHA).startDeploy("a".repeat(40), CONFIRMATION))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);

    verify(client, never()).startDeploy(anyString());
  }

  @Test
  void refusesDeployWhenTheBackendDoesNotKnowItsOwnCommit() {
    // A dev build reports "unknown", and "unknown" must never be something two sides can agree on.
    assertThatThrownBy(() -> service(null).startDeploy("unknown", "REDEPLOY unknow"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);

    verify(client, never()).startDeploy(anyString());
  }

  @Test
  void refusesDeployWhoseConfirmationPhraseDoesNotMatch() {
    assertThatThrownBy(() -> service(SHA).startDeploy(SHA, "REDEPLOY please"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);

    verify(client, never()).startDeploy(anyString());
  }

  @Test
  void deploysTheBackendsOwnCommitRatherThanTheOneTheBrowserSent() {
    when(client.factoryStatus()).thenReturn(instance("software-factory"));
    when(client.deployerStatus()).thenReturn(instance("deployer", ready("deploy")));
    when(client.startDeploy(SHA)).thenReturn(new FactoryRunAccepted("deploy-prod", "run-1", "go"));

    assertThat(service(SHA).startDeploy(SHA, CONFIRMATION).workflowId()).isEqualTo("deploy-prod");

    verify(client).startDeploy(SHA);
  }

  @Test
  void reportsDownstreamConflictAsConflict() {
    when(client.factoryStatus())
        .thenReturn(instance("software-factory", ready("platformbackup")));
    when(client.deployerStatus()).thenReturn(instance("deployer", ready("platformbackup")));
    when(client.startPlatformBackup(anyBoolean()))
        .thenThrow(HttpClientErrorException.create(
            HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY, new byte[0], null));

    assertThatThrownBy(() -> service(SHA).startPlatformBackup(true))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(exception -> {
          ResponseStatusException failure = (ResponseStatusException) exception;
          assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(failure.getReason())
              .isEqualTo("That run is already in progress, so nothing new was started");
        });
  }

  @Test
  void distinguishesDisabledModuleFromAnUnreachableFactory() {
    // Both used to surface as "Software Factory is unavailable", which sent an operator looking
    // for a down container when the real answer was a flag.
    when(client.factoryStatus()).thenReturn(instance("software-factory", ready("cvefix")));
    when(client.deployerStatus()).thenReturn(instance("deployer"));
    when(client.startVulnerabilityScan())
        .thenThrow(HttpClientErrorException.create(
            HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", HttpHeaders.EMPTY, new byte[0], null));

    assertThatThrownBy(() -> service(SHA).startVulnerabilityScan())
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(exception -> assertThat(((ResponseStatusException) exception).getReason())
            .isEqualTo("The Software Factory reports that module as disabled"));
  }

  @Test
  void reportsRejectedCredentialAsBadGatewayRatherThanDisabledModule() {
    when(client.factoryStatus()).thenReturn(instance("software-factory", ready("cvefix")));
    when(client.deployerStatus()).thenReturn(instance("deployer"));
    when(client.startVulnerabilityScan())
        .thenThrow(HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null));

    assertThatThrownBy(() -> service(SHA).startVulnerabilityScan())
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(exception -> {
          ResponseStatusException failure = (ResponseStatusException) exception;
          assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
          assertThat(failure.getReason())
              .isEqualTo("This server is not authorised to call the Software Factory");
        });
  }

  @Test
  void neverForwardsDownstreamErrorBody() {
    // The factory's own messages are written by a process holding credentials, so only this
    // service's fixed strings may cross to the browser.
    when(client.factoryStatus()).thenReturn(instance("software-factory", ready("cvefix")));
    when(client.deployerStatus()).thenReturn(instance("deployer"));
    when(client.startVulnerabilityScan())
        .thenThrow(HttpServerErrorException.create(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Server Error",
            HttpHeaders.EMPTY,
            "LINEAR_API_KEY=lin_api_secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            null));

    assertThatThrownBy(() -> service(SHA).startVulnerabilityScan())
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(exception -> {
          ResponseStatusException failure = (ResponseStatusException) exception;
          assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(failure.getReason()).isEqualTo("Software Factory is unavailable");
          assertThat(failure.getReason()).doesNotContain("lin_api_secret");
        });
  }

  @Test
  void rejectsMalformedWorkflowIdWithoutCallingTheFactory() {
    assertThatThrownBy(() -> service(SHA).progress("../../etc/passwd"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

    verify(client, never()).progress(anyString());
  }

  @Test
  void reportsAnAbsentRunAsNotFound() {
    when(client.progress("gone"))
        .thenThrow(HttpClientErrorException.create(
            HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

    assertThatThrownBy(() -> service(SHA).progress("gone"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void passesRunProgressThrough() {
    when(client.progress("cve-scan-manual-1"))
        .thenReturn(new FactoryRunProgress(
            "cve-scan-manual-1", "run-1", "WORKFLOW_EXECUTION_STATUS_COMPLETED", "COMPLETED",
            "Filed one report", true));

    assertThat(service(SHA).progress("cve-scan-manual-1").terminal()).isTrue();
  }

  private FactoryAdminService service(final String commit) {
    return new FactoryAdminService(client, properties(), runningVersion(commit));
  }

  private static FactoryAdminProperties properties() {
    return new FactoryAdminProperties(
        null, null, "trigger-token", Duration.ofSeconds(2), null, null);
  }

  private static RunningVersion runningVersion(final String commit) {
    if (commit == null) {
      return new RunningVersion(null);
    }
    java.util.Properties properties = new java.util.Properties();
    properties.setProperty("commit", commit);
    return new RunningVersion(new BuildProperties(properties));
  }

  private static FactoryInstanceStatus instance(
      final String container, final ModuleStatus... modules) {
    return new FactoryInstanceStatus(container, Instant.EPOCH, List.of(modules));
  }

  private static ModuleStatus ready(final String key) {
    return new ModuleStatus(
        key, key, true, key, 1, 1, "trigger", null, List.of(), true, null);
  }

  private static ModuleStatus disabled(final String key) {
    return new ModuleStatus(
        key, key, false, key, 1, 0, "trigger", null, List.of(), false,
        "Disabled by configuration");
  }

  private static ModuleStatus module(final FactoryAdminStatus status, final String key) {
    return status.modules().stream()
        .filter(candidate -> key.equals(candidate.key()))
        .findFirst()
        .orElseThrow();
  }
}
