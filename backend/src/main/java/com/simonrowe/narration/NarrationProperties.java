package com.simonrowe.narration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "narration")
public record NarrationProperties(
    boolean enabled,
    String projectId,
    String projectNumber,
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

  public boolean isProviderConfigured() {
    return enabled
        && notBlank(projectId)
        && notBlank(projectNumber)
        && notBlank(location)
        && notBlank(voiceName)
        && notBlank(languageCode)
        && notBlank(bucket);
  }

  private static boolean notBlank(final String value) {
    return value != null && !value.isBlank();
  }
}
