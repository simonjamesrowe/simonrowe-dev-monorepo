package com.simonrowe.factory.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.testsupport.ComposeFile;
import io.temporal.spring.boot.WorkflowImpl;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Pins which container polls which workflow queue.
 *
 * <p>Each container's {@code FACTORY_RUNTIME_ROLE} is its Spring profile, and the profile picks
 * its {@code workflow-packages}. The failure this guards against is silent: a package listed for
 * neither role is a queue nothing polls, so the container is healthy, the schedule fires, and the
 * run never starts. A package listed for both brings back the version mismatch between the two
 * containers that wedged {@code logwatch-daily} from 2026-09-15.
 */
class FactoryWorkflowWorkersTest {

  private static final String WORKFLOW_PACKAGES =
      "spring.temporal.workers-auto-discovery.workflow-packages";

  private static final Set<String> SOFTWARE_FACTORY_PACKAGES =
      Set.of(
          "com.simonrowe.factory.codereview.workflow",
          "com.simonrowe.factory.feedback.workflow",
          "com.simonrowe.factory.cvefix.workflow",
          "com.simonrowe.factory.logwatch.workflow");

  private static final Set<String> DEPLOYER_PACKAGES =
      Set.of(
          "com.simonrowe.factory.deploy.workflow",
          "com.simonrowe.factory.platformbackup.workflow");

  /** Loads application*.yml the way Boot does, with only the given variables in scope. */
  private static Set<String> workflowPackages(final Map<String, Object> variables) {
    StandardEnvironment environment = new StandardEnvironment();
    // The real process environment is dropped so a FACTORY_RUNTIME_ROLE on the build machine
    // cannot decide the outcome.
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().addFirst(new MapPropertySource("test", variables));
    ConfigDataEnvironmentPostProcessor.applyTo(environment);
    return new HashSet<>(
        Binder.get(environment)
            .bind(WORKFLOW_PACKAGES, Bindable.listOf(String.class))
            .orElse(List.of()));
  }

  private static Set<String> workflowPackagesFor(final String role) {
    return workflowPackages(Map.of("FACTORY_RUNTIME_ROLE", role));
  }

  private static Set<String> packagesHoldingWorkflowImplementations() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(WorkflowImpl.class));
    return scanner.findCandidateComponents("com.simonrowe.factory").stream()
        .map(BeanDefinition::getBeanClassName)
        .map(className -> className.substring(0, className.lastIndexOf('.')))
        .collect(Collectors.toSet());
  }

  private static String runtimeRoleIn(final String service) throws IOException {
    return ComposeFile.serviceBlock(ComposeFile.lines(), service).stream()
        .map(String::trim)
        .filter(line -> line.startsWith("FACTORY_RUNTIME_ROLE:"))
        .map(line -> line.substring("FACTORY_RUNTIME_ROLE:".length()).trim())
        .findFirst()
        .orElseThrow(() -> new AssertionError(service + " declares no FACTORY_RUNTIME_ROLE"));
  }

  @Test
  void softwareFactoryPollsOnlyTheWorkflowsWhoseActivitiesRunThere() {
    assertThat(workflowPackagesFor("software-factory"))
        .containsExactlyInAnyOrderElementsOf(SOFTWARE_FACTORY_PACKAGES);
  }

  @Test
  void deployerPollsOnlyDeployAndPlatformBackup() {
    assertThat(workflowPackagesFor("deployer"))
        .containsExactlyInAnyOrderElementsOf(DEPLOYER_PACKAGES);
  }

  @Test
  @DisplayName("with no role set, as in local development, it behaves as software-factory")
  void anUnsetRoleIsSoftwareFactory() {
    assertThat(workflowPackages(Map.of()))
        .containsExactlyInAnyOrderElementsOf(SOFTWARE_FACTORY_PACKAGES);
  }

  @Test
  @DisplayName("every workflow is polled by exactly one container")
  void theTwoRolesPartitionEveryWorkflowPackage() {
    Set<String> softwareFactory = workflowPackagesFor("software-factory");
    Set<String> deployer = workflowPackagesFor("deployer");

    assertThat(softwareFactory).doesNotContainAnyElementsOf(deployer);
    Set<String> both = new HashSet<>(softwareFactory);
    both.addAll(deployer);
    assertThat(both)
        .as("a @WorkflowImpl package missing here is a queue no container polls")
        .containsExactlyInAnyOrderElementsOf(packagesHoldingWorkflowImplementations());
  }

  @Test
  @DisplayName("compose sets the roles the profiles are named after, and nothing overrides them")
  void composeRolesActivateTheProfiles() throws IOException {
    assertThat(runtimeRoleIn("software-factory")).isEqualTo("software-factory");
    assertThat(runtimeRoleIn("deployer")).isEqualTo("deployer");
    for (String service : List.of("software-factory", "deployer")) {
      assertThat(ComposeFile.declaredKeysContaining(
              ComposeFile.serviceBlock(ComposeFile.lines(), service), "SPRING_PROFILES"))
          .as("SPRING_PROFILES_ACTIVE on %s would replace the role profile", service)
          .isEmpty();
    }
  }
}
