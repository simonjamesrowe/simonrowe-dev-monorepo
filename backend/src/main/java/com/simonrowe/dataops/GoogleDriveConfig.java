package com.simonrowe.dataops;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.apache.v5.Apache5HttpTransport;
import com.google.api.client.json.JsonFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
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
  private static final int CONNECT_TIMEOUT_MS = 30_000;
  // Per-request read timeout. Must accommodate a single 10 MB chunk PUT on a slow
  // residential uplink without being so long that real hangs go undetected.
  private static final int READ_TIMEOUT_MS = 5 * 60_000;

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
      // Apache HttpClient transport with TCP_NODELAY and large socket buffers.
      // The default Apache5HttpTransport() pipes data through small kernel
      // socket buffers and Nagle delays per write, which combined with the
      // resumable upload's per-chunk PUT/308 ack pattern caps throughput at
      // ~5 KB/s under the GraalVM native image. Tuning the connection
      // manager unlocks the full ~1 MB/s the host can sustain.
      SocketConfig socketConfig = SocketConfig.custom()
          .setTcpNoDelay(true)
          .setSoKeepAlive(true)
          .setSndBufSize(1024 * 1024)
          .setRcvBufSize(1024 * 1024)
          .setSoTimeout(Timeout.ofMinutes(5))
          .build();
      PoolingHttpClientConnectionManager connectionManager =
          PoolingHttpClientConnectionManagerBuilder.create()
              .setMaxConnTotal(20)
              .setMaxConnPerRoute(10)
              .setDefaultSocketConfig(socketConfig)
              .build();
      CloseableHttpClient httpClient = HttpClients.custom()
          .setConnectionManager(connectionManager)
          .evictIdleConnections(TimeValue.ofMinutes(1))
          .disableContentCompression()
          .build();
      HttpTransport transport = new Apache5HttpTransport(httpClient);
      JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

      GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
          transport, jsonFactory, clientId, clientSecret,
          List.of(DriveScopes.DRIVE)
      ).setAccessType("offline").build();

      TokenResponse tokenResponse = new TokenResponse();
      tokenResponse.setRefreshToken(refreshToken);
      Credential credential = flow.createAndStoreCredential(tokenResponse, "user");

      HttpRequestInitializer initializer = request -> {
        credential.initialize(request);
        request.setConnectTimeout(CONNECT_TIMEOUT_MS);
        request.setReadTimeout(READ_TIMEOUT_MS);
      };

      return new Drive.Builder(transport, jsonFactory, initializer)
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
