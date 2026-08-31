package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parses the REAL {@code docker-compose.prod.yml} shipped as a resource, so this test fails
 * when the parser and the compose file drift apart — which is the whole point of it.
 */
class ProdImageCatalogTest {

  private final ProdImageCatalog catalog = new ProdImageCatalog();

  @Test
  void readsPinnedThirdPartyImages() {
    List<PlatformComponent> components = catalog.components();

    assertThat(components)
        .contains(new PlatformComponent("mongodb", "mongo", "8", false))
        .contains(new PlatformComponent("elasticsearch", "elasticsearch", "9.4.5", false))
        .contains(new PlatformComponent("langfuse", "langfuse/langfuse", "3.212.0", false));
  }

  @Test
  void marksFloatingTagsRatherThanInventingVersion() {
    PlatformComponent alloy = component("alloy");

    assertThat(alloy.tag()).isEqualTo("latest");
    assertThat(alloy.floating()).isTrue();
  }

  @Test
  void treatsAnUntaggedImageAsFloatingLatest() {
    PlatformComponent minio = component("langfuse-minio");

    assertThat(minio.image()).isEqualTo("cgr.dev/chainguard/minio");
    assertThat(minio.tag()).isEqualTo("latest");
    assertThat(minio.floating()).isTrue();
  }

  @Test
  void resolvesComposeVariableDefaults() {
    // software-factory's image is ${FACTORY_IMAGE:-ghcr.io/...:latest}. It is first-party
    // and therefore excluded, so assert on the resolution rule via the deployer's absence
    // and on the fact that no component name survives with a '${' in its image.
    assertThat(catalog.components()).noneMatch(c -> c.image().contains("${"));
  }

  @Test
  void excludesFirstPartyServices() {
    assertThat(catalog.components())
        .extracting(PlatformComponent::name)
        .doesNotContain("backend", "frontend", "software-factory", "deployer");
  }

  @Test
  void excludesOneShotInitContainers() {
    assertThat(catalog.components())
        .extracting(PlatformComponent::name)
        .doesNotContain(
            "uploads-init",
            "temporal-db-init",
            "temporal-schema-init",
            "dependencytrack-db-init",
            "temporal-create-namespace");
  }

  @Test
  void isSortedByServiceNameForStableRendering() {
    List<String> names = catalog.components().stream().map(PlatformComponent::name).toList();

    assertThat(names).isSorted();
  }

  // ProdImageCatalog.parseReference is exercised directly here because the real compose file
  // has no host:port/image entries today, so the registry-port-versus-tag split would
  // otherwise be untested and free to regress silently.

  @Test
  void treatsRegistryPortAsPartOfRepository() {
    PlatformComponent parsed =
        ProdImageCatalog.parseReference("registry-example", "host:5000/image");

    assertThat(parsed.image()).isEqualTo("host:5000/image");
    assertThat(parsed.tag()).isEqualTo("latest");
    assertThat(parsed.floating()).isTrue();
  }

  @Test
  void splitsTagAfterRegistryPort() {
    PlatformComponent parsed =
        ProdImageCatalog.parseReference("registry-example", "host:5000/image:1.2.3");

    assertThat(parsed.image()).isEqualTo("host:5000/image");
    assertThat(parsed.tag()).isEqualTo("1.2.3");
    assertThat(parsed.floating()).isFalse();
  }

  @Test
  void parsesPlainTaggedReference() {
    PlatformComponent parsed = ProdImageCatalog.parseReference("mongodb", "mongo:8");

    assertThat(parsed).isEqualTo(new PlatformComponent("mongodb", "mongo", "8", false));
  }

  @Test
  void parsesPlainUntaggedReferenceAsFloatingLatest() {
    PlatformComponent parsed = ProdImageCatalog.parseReference("mongodb", "mongo");

    assertThat(parsed).isEqualTo(new PlatformComponent("mongodb", "mongo", "latest", true));
  }

  @Test
  void resolvesInterpolatedDefaultAndMarksItFloating() {
    PlatformComponent parsed =
        ProdImageCatalog.parseReference("example", "${FACTORY_IMAGE:-ghcr.io/example/img:latest}");

    assertThat(parsed.image()).isEqualTo("ghcr.io/example/img");
    assertThat(parsed.tag()).isEqualTo("latest");
    assertThat(parsed.floating()).isTrue();
  }

  private PlatformComponent component(final String name) {
    return catalog.components().stream()
        .filter(c -> c.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no component named " + name));
  }
}
