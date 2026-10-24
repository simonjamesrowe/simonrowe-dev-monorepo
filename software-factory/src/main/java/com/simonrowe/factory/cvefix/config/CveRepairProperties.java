package com.simonrowe.factory.cvefix.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether the vulnerability scan files repair tickets the ticket agent picks up by itself.
 *
 * <p>A separate record from {@link CveFixProperties} so that adding it touched no existing
 * constructor call.
 *
 * @param enabled file one repair ticket per fixable backend component, labelled for the agent
 * @param agentLabel the ticket agent's hand-over label ({@code factory.ticket-agent.label})
 * @param osvBaseUrl where fixed versions are looked up
 * @param requestTimeout per OSV request
 * @param projects the Dependency-Track projects whose components get repair tickets: the backend's
 *     Maven components and the frontend's npm packages. Not the image projects, which carry
 *     base-OS packages no manifest here controls
 */
@ConfigurationProperties("factory.cvefix.auto-repair")
public record CveRepairProperties(
    boolean enabled,
    String agentLabel,
    String osvBaseUrl,
    Duration requestTimeout,
    List<String> projects) {

  public CveRepairProperties {
    agentLabel = agentLabel == null || agentLabel.isBlank() ? "factory:agent" : agentLabel;
    osvBaseUrl = osvBaseUrl == null || osvBaseUrl.isBlank() ? "https://api.osv.dev" : osvBaseUrl;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(20) : requestTimeout;
    projects =
        projects == null || projects.isEmpty()
            ? List.of("simonrowe-dev/backend", "simonrowe-dev/frontend")
            : List.copyOf(projects);
  }
}
