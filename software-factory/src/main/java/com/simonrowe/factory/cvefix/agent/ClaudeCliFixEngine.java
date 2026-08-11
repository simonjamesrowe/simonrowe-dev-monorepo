package com.simonrowe.factory.cvefix.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.cvefix.config.CveFixAllowedFiles;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.FixProposal;
import com.simonrowe.factory.git.RepositoryWorkspace;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Bumps vulnerable dependencies with a headless Claude run.
 *
 * <p>The agent is given no {@code Bash} tool: the container carries no Gradle, Node or Docker, so
 * verification happens in CI. It also holds no credential — the Dependency-Track key lives in a
 * Java activity, and {@link ClaudeCliRunner} strips everything outside its allowlist.
 */
@Component
public class ClaudeCliFixEngine implements FixEngine {

  private final CveFixProperties properties;
  private final ClaudeCliRunner runner;
  private final ObjectMapper objectMapper;
  private final String schema;

  /**
   * Creates the engine, loading the structured-output schema from the classpath once.
   *
   * @param properties the CVE-fix module configuration, including the agent's process settings
   * @param runner the shared headless Claude launcher
   * @param objectMapper mapper used both to load the schema and to decode structured output
   */
  public ClaudeCliFixEngine(
      final CveFixProperties properties,
      final ClaudeCliRunner runner,
      final ObjectMapper objectMapper) {
    this.properties = properties;
    this.runner = runner;
    this.objectMapper = objectMapper;
    this.schema = loadSchema();
  }

  @Override
  public FixProposal propose(
      final RepositoryWorkspace workspace,
      final List<ComponentFindings> components,
      final String failureContext,
      final Consumer<String> heartbeat) {
    heartbeat.accept(
        failureContext == null
            ? "Proposing bumps for " + components.size() + " components"
            : "Repairing after a failed build");
    JsonNode structured =
        runner.runStructured(
            new ClaudeCliRunner.Invocation(
                properties.agent().command(),
                properties.agent().model(),
                properties.agent().effort(),
                properties.agent().maxTurns(),
                properties.agent().timeout(),
                tools(),
                allowedTools(),
                schema,
                prompt(components, failureContext),
                workspace.repository()),
            heartbeat);
    return parse(objectMapper, structured);
  }

  /** The tool surface granted to the agent. No {@code Bash}: there is no build toolchain. */
  static List<String> tools() {
    return List.of("Read", "Glob", "Grep", "Edit", "Write");
  }

  /**
   * The fine-grained {@code --allowedTools} entries: read anywhere, edit or write only the
   * allowlisted manifests from {@link CveFixAllowedFiles#ALL}.
   */
  static List<String> allowedTools() {
    List<String> allowed = new ArrayList<>(List.of("Read(./**)", "Glob", "Grep"));
    for (String file : CveFixAllowedFiles.ALL) {
      allowed.add("Edit(./" + file + ")");
      allowed.add("Write(./" + file + ")");
    }
    return List.copyOf(allowed);
  }

  /**
   * Decodes the agent's structured output into a {@link FixProposal}.
   *
   * @throws IllegalStateException if the output does not match {@code cve-fix-schema.json}
   */
  static FixProposal parse(final ObjectMapper objectMapper, final JsonNode structured) {
    try {
      FixProposal proposal = objectMapper.treeToValue(structured, FixProposal.class);
      if (proposal.summary() == null) {
        throw new IllegalStateException("Fix output did not match the cve-fix schema: no summary");
      }
      return proposal;
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Fix output did not match the cve-fix schema", exception);
    }
  }

  /**
   * Builds the fix-agent prompt: the findings to address, and — on a repair attempt — the prior
   * CI failure output.
   */
  static String prompt(final List<ComponentFindings> components, final String failureContext) {
    StringBuilder findings = new StringBuilder();
    for (ComponentFindings component : components) {
      findings
          .append("- ")
          .append(component.purl())
          .append(" (")
          .append(component.componentName())
          .append("@")
          .append(component.componentVersion())
          .append(") advisories: ")
          .append(String.join(", ", component.vulnerabilityIds()))
          .append("\n");
      component.findings().forEach(
          finding ->
              findings
                  .append("    ")
                  .append(finding.vulnerabilityId())
                  .append(" [")
                  .append(finding.severity())
                  .append("] ")
                  .append(finding.recommendation().isBlank()
                      ? "no advisory recommendation"
                      : finding.recommendation())
                  .append("\n"));
    }

    String repair =
        failureContext == null
            ? ""
            : """

            The previous attempt was pushed and CI failed. Its output follows. You may NOT edit
            source code to fix this, on this attempt or any other: the only lever available to
            you is the declared version of a dependency. Either choose a different target
            version for the affected component(s) and say so in the summary, or move the
            component to unfixable with a reason. Do not attempt any other kind of change.

            ```
            %s
            ```
            """
                .formatted(failureContext);

    return """
        You are patching vulnerable dependencies in the simonrowe.dev monorepo. You are in a
        clean checkout of the default branch.

        Hard constraints:
        - You may edit ONLY these files: %s. A change anywhere else fails the run.
        - You have no Bash tool and no build toolchain. Do NOT attempt to build or test. CI
          verifies your change after it is pushed.
        - Never edit source code, ever, on this attempt or any repair attempt. The only change
          you may make is to the declared version of a dependency. If a version bump would
          require a source change to keep building, choose a different target version instead,
          or move the component to unfixable with a reason. Prefer the smallest version bump
          that clears the advisory.

        Dependency-Track findings to address:
        %s
        Process:
        1. Read the manifests to find where each component's version is declared. Most backend
           versions live in gradle/libs.versions.toml, not backend/build.gradle.kts.
        2. For each component, pick the lowest version that clears every listed advisory.
           Dependency-Track does not tell you the fixed version — infer it using your own
           knowledge of released versions for the component; the manifests only show what is
           currently declared, not what is available, and you have no tool to look it up.
        3. Apply the edit. For npm components, prefer adding or updating an "overrides" entry
           in frontend/package.json over editing frontend/package-lock.json directly. You have
           no network access and cannot compute a valid integrity hash: never hand-write a
           "resolved" or "integrity" value in package-lock.json — a fabricated one fails
           `npm ci` in CI and burns the repair budget on an unfixable error.
        4. Anything you cannot fix goes in unfixable, with its purl, its advisory ids exactly as
           listed above, and a reason stating which case applies: no released version clears the
           advisory; the only fix needs a major upgrade of something else; or it is
           transitive-only with no newer direct release.
        5. A partial result is good. Bumping four of six components beats attempting all six
           and breaking the build.
        %s
        Produce the requested structured result.
        """
        .formatted(String.join(", ", CveFixAllowedFiles.ALL), findings, repair);
  }

  private static String loadSchema() {
    try (InputStream input =
        ClaudeCliFixEngine.class.getResourceAsStream("/cve-fix-schema.json")) {
      if (input == null) {
        throw new IllegalStateException("cve-fix-schema.json is missing");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load cve-fix schema", exception);
    }
  }
}
