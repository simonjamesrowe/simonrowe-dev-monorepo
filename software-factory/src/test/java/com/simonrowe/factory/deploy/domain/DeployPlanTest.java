package com.simonrowe.factory.deploy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The deploy-kind policy.
 *
 * <p>Every doubtful case must come out {@link DeployKind#SITE}: a wrong {@code SITE} costs a
 * maintenance page, a wrong {@code FACTORY} exposes a half-recreated site, and a wrong {@code
 * NOTHING} reports a commit as deployed while the previous one runs.
 */
class DeployPlanTest {

  private static final List<String> SERVICES =
      List.of("backend", "frontend", "software-factory", "page-reader");

  private static PlanFacts facts(
      final List<String> images, final List<String> config, final List<String> hostFiles) {
    return new PlanFacts(images, config, hostFiles, true, "facts");
  }

  @Test
  void deploysEverythingWhenThereAreNoFacts() {
    DeployPlan plan = DeployPlan.decide(null, SERVICES);

    assertThat(plan.kind()).isEqualTo(DeployKind.SITE);
    assertThat(plan.services()).isEqualTo(SERVICES);
    assertThat(plan.raisesMaintenancePage()).isTrue();
    assertThat(plan.scope()).isNull();
  }

  @Test
  void deploysEverythingWhenTheFactsCouldNotBeRead() {
    // Unreadable facts carry empty lists, which on their own would read as "nothing changed".
    DeployPlan plan = DeployPlan.decide(PlanFacts.unreadable("docker was unreachable"), SERVICES);

    assertThat(plan.kind()).isEqualTo(DeployKind.SITE);
    assertThat(plan.detail()).contains("docker was unreachable");
  }

  @Test
  void nothingChangedIsNothingToDo() {
    DeployPlan plan = DeployPlan.decide(facts(List.of(), List.of(), List.of()), SERVICES);

    assertThat(plan.kind()).isEqualTo(DeployKind.NOTHING);
    assertThat(plan.raisesMaintenancePage()).isFalse();
  }

  @Test
  void factoryImagesAloneAreFactoryOnlyAndScopedToTheFactory() {
    DeployPlan plan =
        DeployPlan.decide(facts(List.of("software-factory"), List.of(), List.of()), SERVICES);

    assertThat(plan.kind()).isEqualTo(DeployKind.FACTORY);
    assertThat(plan.raisesMaintenancePage()).isFalse();
    // Both factory services, in request order, so pull records a rollback target for each.
    assertThat(plan.scope())
        .isEqualTo(new PhaseScope(List.of("software-factory", "page-reader"), false));
  }

  @Test
  void factoryConfigurationAloneIsFactoryOnly() {
    DeployPlan plan =
        DeployPlan.decide(facts(List.of(), List.of("page-reader"), List.of()), SERVICES);

    assertThat(plan.kind()).isEqualTo(DeployKind.FACTORY);
  }

  @Test
  void anySiteServiceMakesItSiteDeploy() {
    DeployPlan image =
        DeployPlan.decide(
            facts(List.of("software-factory", "backend"), List.of(), List.of()), SERVICES);
    DeployPlan config =
        DeployPlan.decide(
            facts(List.of("software-factory"), List.of("nginx"), List.of()), SERVICES);

    assertThat(image.kind()).isEqualTo(DeployKind.SITE);
    assertThat(config.kind()).isEqualTo(DeployKind.SITE);
    assertThat(config.services()).isEqualTo(SERVICES);
  }

  @Test
  void serviceNobodyClassifiedCountsAsTheSite() {
    // A service added to the compose file later is the site until someone says otherwise.
    DeployPlan plan =
        DeployPlan.decide(facts(List.of(), List.of("brand-new-service"), List.of()), SERVICES);

    assertThat(plan.kind()).isEqualTo(DeployKind.SITE);
  }

  @Test
  void bindMountedFilesMakeItSiteDeployEvenWithNothingElseChanged() {
    // nginx's routes live in config/nginx and are in no hash. Missing this would report a route
    // change as "nothing to deploy" and never restart nginx to load it.
    DeployPlan plan =
        DeployPlan.decide(
            facts(List.of("software-factory"), List.of(), List.of("config/nginx/nginx-proxy.conf")),
            SERVICES);

    assertThat(plan.kind()).isEqualTo(DeployKind.SITE);
    assertThat(plan.detail()).contains("config/nginx/nginx-proxy.conf");
  }

  @Test
  void requestWithNoFactoryServicesCannotBeNarrowedToThem() {
    // The script reads an empty SERVICES as "every image", so an empty scope must never exist.
    DeployPlan plan =
        DeployPlan.decide(
            facts(List.of(), List.of("software-factory"), List.of()), List.of("backend"));

    assertThat(plan.kind()).isEqualTo(DeployKind.SITE);
    assertThat(plan.services()).containsExactly("backend");
  }

  @Test
  void anEmptyScopeIsRefused() {
    assertThatThrownBy(() -> new PhaseScope(List.of(), false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
