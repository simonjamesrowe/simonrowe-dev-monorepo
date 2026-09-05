package com.simonrowe.factoryadmin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Internal software-factory endpoints and credentials used only by the backend proxy.
 *
 * <p>Two separate tokens, mirroring the two the factory itself checks: {@code triggerToken}
 * authorises starting work (a review, a deploy, a platform backup), while {@code readToken}
 * authorises only {@code GET /api/factory/flow/{nodeKey}} — the one call this backend also makes
 * to the {@code deployer}, which holds the read token but never the trigger token.
 */
@ConfigurationProperties("factory-admin")
public record FactoryAdminProperties(
    String factoryBaseUrl,
    String deployerBaseUrl,
    String triggerToken,
    String readToken,
    Duration timeout,
    String owner,
    String repository) {

  public FactoryAdminProperties {
    factoryBaseUrl = defaulted(factoryBaseUrl, "http://software-factory:8090");
    deployerBaseUrl = defaulted(deployerBaseUrl, "http://deployer:8090");
    triggerToken = triggerToken == null ? "" : triggerToken;
    readToken = readToken == null ? "" : readToken;
    timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
    owner = defaulted(owner, "simonjamesrowe");
    repository = defaulted(repository, "simonrowe-dev-monorepo");
  }

  private static String defaulted(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
