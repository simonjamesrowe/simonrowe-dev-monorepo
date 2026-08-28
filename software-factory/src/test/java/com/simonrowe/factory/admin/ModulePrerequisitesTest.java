package com.simonrowe.factory.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.feedback.config.FeedbackProperties;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.platformbackup.config.PlatformBackupProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feedback, vulnerability scanning, Linear filing and platform backup are on by default now,
 * while every credential and host path they need still defaults to empty. These tests pin the
 * distinction the status page rests on: enabled is not the same as able to work.
 */
class ModulePrerequisitesTest {

  private static final String API_KEY = "linear-key";
  private static final String TEAM_KEY = "SIM";

  @TempDir private Path temp;

  @Test
  void reportsCodeReviewAsAlwaysConfigured() {
    // It has no enable flag by design: reviewing pull requests is the factory's original job.
    assertThat(prerequisites(false, false, false, false, null).configured("codereview")).isTrue();
  }

  @Test
  void mapsEachModuleToItsOwnFlag() {
    ModulePrerequisites none = prerequisites(false, false, false, false, null);
    assertThat(none.configured("feedback")).isFalse();
    assertThat(none.configured("cvefix")).isFalse();
    assertThat(none.configured("linear")).isFalse();
    assertThat(none.configured("platformbackup")).isFalse();

    ModulePrerequisites all = prerequisites(true, true, true, true, null);
    assertThat(all.configured("feedback")).isTrue();
    assertThat(all.configured("cvefix")).isTrue();
    assertThat(all.configured("linear")).isTrue();
    assertThat(all.configured("platformbackup")).isTrue();
  }

  @Test
  void countsDeployAsConfiguredUnderEitherOfItsTwoFlags() {
    // A container that can only trigger and a container that can only execute are both
    // meaningfully part of deploying, and the two flags are set on different containers.
    assertThat(deployPrerequisites(true, false, null).configured("deploy")).isTrue();
    assertThat(deployPrerequisites(false, true, null).configured("deploy")).isTrue();
    assertThat(deployPrerequisites(false, false, null).configured("deploy")).isFalse();
  }

  @Test
  void reportsNothingMissingForDisabledModule() {
    // An unset credential on a switched-off module is not a fault, and reporting it as one would
    // bury the only fact that matters.
    ModulePrerequisites prerequisites = prerequisites(false, false, false, false, null);
    for (String key : ModulePrerequisites.KEYS) {
      assertThat(prerequisites.missingFor(key, false)).isEmpty();
    }
  }

  @Test
  void reportsAnEnabledLinearWithNoCredential() {
    ModulePrerequisites prerequisites =
        prerequisites(false, false, true, false, null, "", "");

    assertThat(prerequisites.missingFor("linear", true))
        .containsExactly("Linear API key is not set", "Linear team key is not set");
  }

  @Test
  void reportsNothingWhenLinearIsFullyConfigured() {
    assertThat(prerequisites(false, false, true, false, null, API_KEY, TEAM_KEY)
        .missingFor("linear", true))
        .isEmpty();
  }

  @Test
  void reportsFeedbackThatCannotFileItsIssue() {
    // Feedback files its Linear issue before distilling and fails non-retryably when it cannot,
    // so with the sink off the loop stops entirely rather than degrading to PR-only. Reporting
    // that as healthy would be the console's most misleading possible answer.
    assertThat(prerequisites(true, false, false, false, null).missingFor("feedback", true))
        .containsExactly("Linear filing is disabled, and feedback files its issue first");
  }

  @Test
  void reportsNothingForFeedbackWhenTheSinkIsOn() {
    assertThat(prerequisites(true, false, true, false, null, API_KEY, TEAM_KEY)
        .missingFor("feedback", true))
        .isEmpty();
  }

  @Test
  void reportsVulnerabilityScanningWithNoDependencyTrackKeyOrIssueSink() {
    // Both matter, and for different reasons: with no API key it cannot read findings, and with
    // Linear off it has nowhere to put them, since this flow deliberately no longer opens PRs.
    assertThat(prerequisites(false, true, false, false, null).missingFor("cvefix", true))
        .containsExactly(
            "Dependency-Track API key is not set",
            "Linear filing is disabled, so findings have nowhere to go");
  }

  @Test
  void reportsNothingWhenVulnerabilityScanningCanRun() {
    assertThat(prerequisites(false, true, true, false, null, API_KEY, TEAM_KEY, "dt-key")
        .missingFor("cvefix", true))
        .isEmpty();
  }

  @Test
  void reportsAnUnsetBackupScriptPath() {
    assertThat(prerequisites(false, false, false, true, null).missingFor("platformbackup", true))
        .containsExactly("The platform backup script path is not set");
  }

  @Test
  void reportsBackupScriptThatIsNotWhereItShouldBe() {
    // The exact failure the platform-backup variables shipped with once: a path that stopped
    // existing when the deploy directory moved, invisible until the first real capture.
    assertThat(prerequisites(false, false, false, true, temp.resolve("gone.sh").toString())
        .missingFor("platformbackup", true))
        .containsExactly("The platform backup script is not present at its configured path");
  }

  @Test
  void reportsNothingWhenTheBackupScriptExists() throws IOException {
    Path script = Files.writeString(temp.resolve("backup-platform.sh"), "#!/bin/sh\n");

    assertThat(prerequisites(false, false, false, true, script.toString())
        .missingFor("platformbackup", true))
        .isEmpty();
  }

  @Test
  void checksTheDeployScriptOnlyOnTheContainerThatExecutesDeploys() {
    // software-factory triggers deploys and holds no deploy script; only the deployer runs one,
    // so a trigger-only container must not be reported as broken for a file it never needs.
    assertThat(deployPrerequisites(false, true, null).missingFor("deploy", true)).isEmpty();
  }

  @Test
  void reportsDeployScriptThatIsNotWhereItShouldBe() throws IOException {
    // Note the default: DeployProperties falls back to /workspace/repo/scripts/restart-prod.sh,
    // a path that stopped existing when the deploy directory moved to its own host path. So the
    // executing container reporting nothing here means it was configured, not that it defaulted.
    assertThat(deployPrerequisites(true, false, null).missingFor("deploy", true))
        .containsExactly("The production deploy script is not present at its configured path");

    Path script = Files.writeString(temp.resolve("restart-prod.sh"), "#!/bin/sh\n");
    assertThat(deployPrerequisites(true, false, script.toString()).missingFor("deploy", true))
        .isEmpty();
  }

  private ModulePrerequisites prerequisites(
      final boolean feedback,
      final boolean cvefix,
      final boolean linear,
      final boolean backup,
      final String backupScript) {
    return prerequisites(feedback, cvefix, linear, backup, backupScript, "", "");
  }

  private ModulePrerequisites prerequisites(
      final boolean feedback,
      final boolean cvefix,
      final boolean linear,
      final boolean backup,
      final String backupScript,
      final String linearApiKey,
      final String linearTeamKey) {
    return prerequisites(
        feedback, cvefix, linear, backup, backupScript, linearApiKey, linearTeamKey, "");
  }

  private ModulePrerequisites prerequisites(
      final boolean feedback,
      final boolean cvefix,
      final boolean linear,
      final boolean backup,
      final String backupScript,
      final String linearApiKey,
      final String linearTeamKey,
      final String dependencyTrackKey) {
    return new ModulePrerequisites(
        new FeedbackProperties(feedback, null, null, null, null, null, null, null, null),
        new CveFixProperties(
            cvefix, null, null, null, null, null, null, null,
            new CveFixProperties.DependencyTrack(null, dependencyTrackKey, null, null),
            null, null),
        deployProperties(false, false, null),
        new LinearProperties(
            linear, linearApiKey, null, linearTeamKey, null, false, null, null),
        new PlatformBackupProperties(backup, backupScript, null, null));
  }

  private ModulePrerequisites deployPrerequisites(
      final boolean enabled, final boolean triggerEnabled, final String script) {
    return new ModulePrerequisites(
        new FeedbackProperties(false, null, null, null, null, null, null, null, null),
        new CveFixProperties(
            false, null, null, null, null, null, null, null, null, null, null),
        deployProperties(enabled, triggerEnabled, script),
        new LinearProperties(false, null, null, null, null, false, null, null),
        new PlatformBackupProperties(false, null, null, null));
  }

  private static DeployProperties deployProperties(
      final boolean enabled, final boolean triggerEnabled, final String script) {
    return new DeployProperties(
        enabled, triggerEnabled, null, null, null, null, null, script, null, null, null, null,
        null, null, null, null, null);
  }
}
