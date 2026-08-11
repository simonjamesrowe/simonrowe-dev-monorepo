package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards which services a redeploy actually touches, against the real {@code application.yml}.
 *
 * <p>{@code software-factory} was missing from the list, so CI published a new image for the
 * automated code reviewer on every merge and nothing ever deployed it. It sat on a stale image
 * while the rest of the stack moved, and failed quietly — without commenting on the pull requests
 * it skipped. A regression here is someone trimming the list, which is silent by nature, so assert
 * the deployed configuration rather than a Java constant.
 */
class RedeployServicesTest {

  private static List<String> configuredServices() throws Exception {
    List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"));
    List<String> services = new ArrayList<>();
    for (PropertySource<?> source : loaded) {
      for (int i = 0; ; i++) {
        Object value = source.getProperty("redeploy.services[" + i + "]");
        if (value == null) {
          break;
        }
        services.add(value.toString());
      }
    }
    return services;
  }

  @Test
  void redeployCoversEveryServiceBuiltFromThisRepository() throws Exception {
    assertThat(configuredServices())
        .containsExactlyInAnyOrder("backend", "frontend", "nginx", "software-factory");
  }

  @Test
  void softwareFactoryRestartsSeparatelyFromFrontendAndNginx() throws Exception {
    List<String> configured = configuredServices();

    assertThat(RedeployService.servicesRestartedTogether(configured))
        .containsExactlyInAnyOrder("frontend", "nginx")
        .doesNotContain("backend", "software-factory");
  }

  @Test
  void backendIsNeverRestartedInlineBecauseItWouldKillTheComposeProcess() {
    assertThat(RedeployService.servicesRestartedTogether(List.of("backend")))
        .isEmpty();
  }

  @Test
  void isolatedServicesAreOnlyThoseNeedingUnhealthyDependenciesSkipped() throws Exception {
    assertThat(RedeployService.servicesRestartedInIsolation(configuredServices()))
        .containsExactly("software-factory");
  }

  @Test
  void anAbsentSoftwareFactoryIsSimplyNotRestarted() {
    assertThat(RedeployService.servicesRestartedInIsolation(List.of("backend", "frontend")))
        .isEmpty();
  }
}
