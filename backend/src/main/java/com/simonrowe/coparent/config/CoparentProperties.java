package com.simonrowe.coparent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime configuration for the isolated CoParent product module. */
@ConfigurationProperties("coparent")
public record CoparentProperties(
    boolean enabled,
    String database,
    String appUrl,
    String auth0EmailClaim,
    String emailFrom,
    boolean emailEnabled,
    String sourceDatabase,
    Assistant assistant
) {
  /** Configuration for the private proposal-only CoParent assistant. */
  public record Assistant(boolean enabled, String model) {
  }
}
