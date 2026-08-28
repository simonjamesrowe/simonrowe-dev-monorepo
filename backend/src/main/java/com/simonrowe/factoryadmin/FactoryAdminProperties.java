package com.simonrowe.factoryadmin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Internal software-factory endpoints and credential used only by the backend proxy. */
@ConfigurationProperties("factory-admin")
public record FactoryAdminProperties(
    String factoryBaseUrl,
    String deployerBaseUrl,
    String triggerToken,
    Duration timeout,
    String owner,
    String repository) {

  public FactoryAdminProperties {
    factoryBaseUrl = defaulted(factoryBaseUrl, "http://software-factory:8090");
    deployerBaseUrl = defaulted(deployerBaseUrl, "http://deployer:8090");
    triggerToken = triggerToken == null ? "" : triggerToken;
    timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
    owner = defaulted(owner, "simonjamesrowe");
    repository = defaulted(repository, "simonrowe-dev-monorepo");
  }

  private static String defaulted(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
