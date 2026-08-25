package com.simonrowe.narration;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(NarrationProperties.class)
public class GoogleTextToSpeechConfig {

  private static final Logger LOG =
      LoggerFactory.getLogger(GoogleTextToSpeechConfig.class);
  private static final String CLOUD_PLATFORM_SCOPE =
      "https://www.googleapis.com/auth/cloud-platform";

  @Bean
  @Nullable
  public GoogleCredentials narrationGoogleCredentials(
      final NarrationProperties properties
  ) {
    if (!properties.isProviderConfigured()) {
      LOG.info("Narration is disabled or incompletely configured");
      return null;
    }
    if (properties.usesApiKey()) {
      // An API key needs no token exchange, so there is nothing to load. Returning null
      // here is a configured state, not a failure — the provider checks usesApiKey()
      // before it checks for credentials.
      LOG.info("Narration is using API key authentication");
      return null;
    }
    try {
      return GoogleCredentials.getApplicationDefault()
          .createScoped(List.of(CLOUD_PLATFORM_SCOPE));
    } catch (IOException ex) {
      LOG.error("Unable to load Google Application Default Credentials for narration");
      return null;
    }
  }

  @Bean
  public RestClient googleNarrationRestClient(final RestClient.Builder builder) {
    return builder.build();
  }
}
