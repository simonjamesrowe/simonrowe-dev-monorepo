package com.simonrowe.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for Langfuse trace enrichment and score submission. */
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

  private String host = "https://langfuse.simonrowe.dev";
  private String publicKey;
  private String secretKey;

  /** Tags traces so local traffic is distinguishable from production in one project. */
  private String environment = "development";

  /** Off by default: score submission needs project keys, which are not always present. */
  private boolean scoresEnabled;

  /** On by default: capturing prompt/completion content is the point of this feature. */
  private boolean contentCaptureEnabled = true;

  public String getHost() {
    return host;
  }

  public void setHost(final String host) {
    this.host = host;
  }

  public String getPublicKey() {
    return publicKey;
  }

  public void setPublicKey(final String publicKey) {
    this.publicKey = publicKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public void setSecretKey(final String secretKey) {
    this.secretKey = secretKey;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(final String environment) {
    this.environment = environment;
  }

  public boolean isScoresEnabled() {
    return scoresEnabled;
  }

  public void setScoresEnabled(final boolean scoresEnabled) {
    this.scoresEnabled = scoresEnabled;
  }

  public boolean isContentCaptureEnabled() {
    return contentCaptureEnabled;
  }

  public void setContentCaptureEnabled(final boolean contentCaptureEnabled) {
    this.contentCaptureEnabled = contentCaptureEnabled;
  }
}
