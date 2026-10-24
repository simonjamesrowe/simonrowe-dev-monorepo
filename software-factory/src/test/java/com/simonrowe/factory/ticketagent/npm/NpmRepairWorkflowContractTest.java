package com.simonrowe.factory.ticketagent.npm;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.ticketagent.config.NpmRepairProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ties the factory to the workflow it starts. Neither side runs the other in a test, so a renamed
 * run, artifact or file would otherwise surface only as an npm repair that never finds its run.
 * Also pins the workflow's posture: no write permission, no secret, inputs only through env.
 */
class NpmRepairWorkflowContractTest {

  /** Gradle runs tests from {@code software-factory/}, one level below the repo root. */
  private static final Path WORKFLOW = Path.of("..", ".github", "workflows",
      new NpmRepairProperties(null, null, null, null, 0).workflow());

  private static String workflow() throws IOException {
    return Files.readString(WORKFLOW);
  }

  @Test
  void runNameCarriesTheRequestTheFactoryLooksFor() throws IOException {
    assertThat(workflow()).contains(
        "run-name: " + NpmRepairActions.RUN_NAME_PREFIX + "${{ inputs.request_id }}\n");
  }

  @Test
  void uploadsTheArtifactAndEntriesTheFactoryReads() throws IOException {
    String text = workflow();
    assertThat(text).contains("name: " + NpmRepairActions.ARTIFACT + "\n", "path: out/\n");
    for (String entry : NpmRepairActions.ENTRIES) {
      assertThat(text).as(entry).contains("out/" + entry);
    }
  }

  @Test
  void runsWithReadOnlyContentsAndNoSecret() throws IOException {
    String text = workflow();
    assertThat(text).contains("permissions:\n  contents: read\n").doesNotContain("secrets.");
    assertThat(text).doesNotContain(": write").doesNotContain("write-all");
    assertThat(text).contains("on:\n  workflow_dispatch:\n");
  }

  @Test
  void neverRunsPackageCodeOrInstalls() throws IOException {
    assertThat(workflow()).contains("--package-lock-only --ignore-scripts")
        .doesNotContain("npm ci").doesNotContain("npm install");
  }

  /** An input expanded inside a script is a shell injection; they go through env only. */
  @Test
  void inputsReachScriptsOnlyThroughEnv() throws IOException {
    List<String> scriptLines = new ArrayList<>();
    boolean inScript = false;
    int scriptIndent = 0;
    for (String line : workflow().split("\n", -1)) {
      int indent = line.length() - line.stripLeading().length();
      if (inScript && !line.isBlank() && indent < scriptIndent) {
        inScript = false;
      }
      if (inScript) {
        scriptLines.add(line);
      }
      if (line.stripLeading().startsWith("run: |")) {
        inScript = true;
        scriptIndent = indent + 2;
      }
    }
    assertThat(scriptLines).isNotEmpty();
    assertThat(scriptLines).noneMatch(line -> line.contains("${{"));
  }
}
