package com.simonrowe.factory.ticketagent.npm;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.ticketagent.config.NpmRepairProperties;
import com.simonrowe.factory.ticketagent.config.TicketAgentProperties;
import com.simonrowe.factory.ticketagent.github.TicketAgentGitHub;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The GitHub Actions calls an npm repair makes: start the lockfile workflow, find and follow its
 * run, and download what it produced.
 *
 * <p>Starting a workflow needs the App's {@code actions: write}, an optional permission. Without
 * it GitHub answers 403 and the repair is handed back to a person, saying which grant is missing.
 *
 * <p>Never follows a redirect on its own. The artifact download answers with a signed
 * blob-storage URL, which is checked against the hosts GitHub stores artifacts on and fetched
 * without the GitHub token, exactly as job logs are.
 */
@Component
public class NpmRepairActions {

  /** The run's name, set by the workflow's {@code run-name}, followed by the request id. */
  static final String RUN_NAME_PREFIX = "npm lockfile repair ";

  /** The artifact the workflow uploads. */
  static final String ARTIFACT = "npm-lockfile-repair";

  /** The only entries read from the artifact. */
  static final Set<String> ENTRIES = Set.of("package-lock.json", "result.json");

  private static final String API_VERSION = "2026-03-10";
  private static final String USER_AGENT = "simonrowe-ticket-agent";

  private final CodeReviewProperties codeReviewProperties;
  private final TicketAgentProperties properties;
  private final NpmRepairProperties npm;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public NpmRepairActions(
      final CodeReviewProperties codeReviewProperties,
      final TicketAgentProperties properties,
      final NpmRepairProperties npm,
      final GitHubCredentials credentials,
      final ObjectMapper objectMapper) {
    this.codeReviewProperties = codeReviewProperties;
    this.properties = properties;
    this.npm = npm;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(codeReviewProperties.github().requestTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * A workflow run, as far as the repair needs one.
   *
   * @param id the run id
   * @param status {@code queued}, {@code in_progress}, {@code completed}...
   * @param conclusion {@code success}, {@code failure}... once completed, otherwise empty
   * @param url the run's page
   */
  public record Run(long id, String status, String conclusion, String url) {

    /** Whether the run has finished, however it ended. */
    public boolean completed() {
      return "completed".equals(status);
    }
  }

  /**
   * Starts the workflow on the configured branch.
   *
   * @param requestId the id the run's name will carry
   * @param packages space-separated {@code name@fixedVersion} pairs
   * @param installationId the App installation
   * @throws NpmRepairUnavailableException when the App may not start workflows
   */
  public void dispatch(final String requestId, final String packages, final Long installationId) {
    ObjectNode payload = objectMapper.createObjectNode().put("ref", npm.ref());
    payload.putObject("inputs").put("request_id", requestId).put("packages", packages);
    HttpResponse<String> response =
        send("POST", workflowPath() + "/dispatches", payload, installationId);
    if (response.statusCode() == 403) {
      throw new NpmRepairUnavailableException(
          "the GitHub App may not start workflows: grant it `actions: write`");
    }
    if (response.statusCode() == 404) {
      throw new NpmRepairUnavailableException(
          "`.github/workflows/%s` is not on `%s`".formatted(npm.workflow(), npm.ref()));
    }
    requireOk(response, "starting the npm lockfile workflow");
  }

  /**
   * The run started for a request, once GitHub lists it.
   *
   * @param requestId the id passed to {@link #dispatch}
   * @param installationId the App installation
   * @return the run, or empty when GitHub does not list it yet
   */
  public Optional<Run> findRun(final String requestId, final Long installationId) {
    HttpResponse<String> response =
        send("GET", workflowPath() + "/runs?event=workflow_dispatch&per_page=30", null,
            installationId);
    requireOk(response, "listing the npm lockfile workflow's runs");
    for (JsonNode run : parse(response).path("workflow_runs")) {
      if ((RUN_NAME_PREFIX + requestId).equals(run.path("display_title").asText(""))) {
        return Optional.of(toRun(run));
      }
    }
    return Optional.empty();
  }

  /**
   * A run's current state.
   *
   * @param runId the run
   * @param installationId the App installation
   * @return the run
   */
  public Run run(final long runId, final Long installationId) {
    HttpResponse<String> response =
        send("GET", repoPath() + "/actions/runs/" + runId, null, installationId);
    requireOk(response, "reading the npm lockfile workflow run");
    return toRun(parse(response));
  }

  /**
   * The artifact's files, by name: {@code package-lock.json} and {@code result.json}.
   *
   * @param runId the finished run
   * @param installationId the App installation
   * @return the entries found, each read whole; other entries are ignored
   */
  public Map<String, byte[]> artifactFiles(final long runId, final Long installationId) {
    HttpResponse<String> listing =
        send("GET", repoPath() + "/actions/runs/" + runId + "/artifacts", null, installationId);
    requireOk(listing, "listing the run's artifacts");
    long artifactId = -1;
    for (JsonNode artifact : parse(listing).path("artifacts")) {
      if (ARTIFACT.equals(artifact.path("name").asText(""))
          && !artifact.path("expired").asBoolean(false)) {
        artifactId = artifact.path("id").asLong(-1);
      }
    }
    if (artifactId < 0) {
      throw new IllegalStateException("the run uploaded no `" + ARTIFACT + "` artifact");
    }
    HttpResponse<String> redirect =
        send("GET", repoPath() + "/actions/artifacts/" + artifactId + "/zip", null,
            installationId);
    Optional<URI> location =
        redirect.headers().firstValue("location").flatMap(TicketAgentGitHub::logLocation);
    if (redirect.statusCode() / 100 != 3 || location.isEmpty()) {
      throw new IllegalStateException(
          "the artifact download answered " + redirect.statusCode() + " with no usable redirect");
    }
    return download(location.get());
  }

  private Map<String, byte[]> download(final URI location) {
    try {
      HttpResponse<InputStream> response =
          httpClient.send(
              HttpRequest.newBuilder(location)
                  .timeout(codeReviewProperties.github().requestTimeout())
                  .header("User-Agent", USER_AGENT)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() != 200) {
          throw new IllegalStateException(
              "the artifact download answered " + response.statusCode());
        }
        return unzip(body, npm.maxArtifactBytes());
      }
    } catch (IOException exception) {
      throw new IllegalStateException("the artifact could not be downloaded", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted downloading the artifact", exception);
    }
  }

  /**
   * Reads {@link #ENTRIES} from a zip, by exact name, refusing anything past {@code maxBytes} in
   * total. Entry names are only compared, never used as paths, so a crafted name cannot write
   * anywhere.
   */
  static Map<String, byte[]> unzip(final InputStream zip, final long maxBytes) throws IOException {
    Map<String, byte[]> files = new HashMap<>();
    long total = 0;
    try (ZipInputStream entries = new ZipInputStream(zip)) {
      ZipEntry entry;
      while ((entry = entries.getNextEntry()) != null) {
        if (entry.isDirectory() || !ENTRIES.contains(entry.getName())) {
          continue;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = entries.read(buffer)) > 0) {
          total += read;
          if (total > maxBytes) {
            throw new IllegalStateException("the artifact is larger than " + maxBytes + " bytes");
          }
          out.write(buffer, 0, read);
        }
        files.put(entry.getName(), out.toByteArray());
      }
    }
    return files;
  }

  private static Run toRun(final JsonNode run) {
    return new Run(
        run.path("id").asLong(),
        run.path("status").asText(""),
        run.path("conclusion").asText(""),
        run.path("html_url").asText(""));
  }

  private String workflowPath() {
    return repoPath() + "/actions/workflows/" + npm.workflow();
  }

  private String repoPath() {
    return "/repos/" + properties.owner() + "/" + properties.repository();
  }

  private JsonNode parse(final HttpResponse<String> response) {
    try {
      String body = response.body();
      return body == null || body.isBlank()
          ? objectMapper.createObjectNode()
          : objectMapper.readTree(body);
    } catch (JacksonException exception) {
      throw new IllegalStateException("GitHub response was not valid JSON", exception);
    }
  }

  private static void requireOk(final HttpResponse<String> response, final String doing) {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String body = response.body() == null ? "" : response.body();
      throw new IllegalStateException(
          "GitHub answered %d %s: %s".formatted(response.statusCode(), doing,
              body.length() <= 300 ? body : body.substring(0, 300)));
    }
  }

  private HttpResponse<String> send(
      final String method, final String path, final JsonNode payload, final Long installationId) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder()
              .uri(URI.create(codeReviewProperties.github().apiBaseUrl() + path))
              .timeout(codeReviewProperties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", USER_AGENT)
              .header("Authorization", "Bearer " + credentials.accessToken(installationId));
      if (payload == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request
            .header("Content-Type", "application/json")
            .method(
                method,
                HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
      }
      return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub request interrupted", exception);
    } catch (IOException | JacksonException exception) {
      throw new IllegalStateException("GitHub request failed", exception);
    }
  }
}
