package com.simonrowe.dataops;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GoogleDriveConfig {

  private static final Logger LOG = LoggerFactory.getLogger(GoogleDriveConfig.class);

  @Value("${google.drive.client-id:}")
  private String clientId;

  @Value("${google.drive.client-secret:}")
  private String clientSecret;

  @Value("${google.drive.refresh-token:}")
  private String refreshToken;

  @Bean
  public Drive googleDriveClient() {
    if (isBlank(clientId) || isBlank(clientSecret) || isBlank(refreshToken)) {
      LOG.warn("Google Drive OAuth2 credentials not configured — "
          + "backup/restore features will be unavailable. "
          + "Set GOOGLE_DRIVE_CLIENT_ID, GOOGLE_DRIVE_CLIENT_SECRET, "
          + "and GOOGLE_DRIVE_REFRESH_TOKEN.");
      return null;
    }

    try {
      HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
      JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

      GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
          transport, jsonFactory, clientId, clientSecret,
          List.of(DriveScopes.DRIVE)
      ).setAccessType("offline").build();

      TokenResponse tokenResponse = new TokenResponse();
      tokenResponse.setRefreshToken(refreshToken);
      Credential credential = flow.createAndStoreCredential(tokenResponse, "user");

      return new Drive.Builder(transport, jsonFactory, credential)
          .setApplicationName("simonrowe-backup")
          .build();
    } catch (Exception ex) {
      LOG.error("Failed to initialize Google Drive client", ex);
      return null;
    }
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
