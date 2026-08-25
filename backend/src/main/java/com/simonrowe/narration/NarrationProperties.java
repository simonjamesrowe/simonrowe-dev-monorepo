package com.simonrowe.narration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "narration")
public record NarrationProperties(
    boolean enabled,
    String projectId,
    String projectNumber,
    String apiKey,
    String location,
    String voiceName,
    String languageCode,
    String bucket,
    int maxBlogCharacters,
    long monthlyCharacterLimit,
    Duration pollInterval,
    Duration operationTimeout,
    Duration leaseDuration,
    Duration recoveryDelay
) {

  /**
   * Whether an API key is configured, in which case it is used in preference to
   * Application Default Credentials.
   *
   * <p>An API key authenticates {@code text:synthesize}, which is the only endpoint on
   * the live synthesis path — {@code NarrationRequestConsumer} routes every script
   * through chunked synchronous synthesis because {@code maxImmediateBytes()} is always
   * positive for this provider. It would not authenticate the Long Audio route's
   * Cloud Storage download, which is why {@link #isProviderConfigured()} still demands
   * a project number and bucket when falling back to ADC.
   */
  public boolean usesApiKey() {
    return notBlank(apiKey);
  }

  /**
   * Whether narration can run at all.
   *
   * <p>{@code projectNumber} and {@code bucket} are required only for the Long Audio
   * route, which needs a {@code projects/<number>/locations/<location>} resource path and
   * a Cloud Storage bucket to write into. An API-key deployment never takes that route,
   * so it is not made to provision a bucket it will never use.
   */
  public boolean isProviderConfigured() {
    return enabled
        && notBlank(projectId)
        && notBlank(location)
        && notBlank(voiceName)
        && notBlank(languageCode)
        && (usesApiKey() || (notBlank(projectNumber) && notBlank(bucket)));
  }

  private static boolean notBlank(final String value) {
    return value != null && !value.isBlank();
  }
}
