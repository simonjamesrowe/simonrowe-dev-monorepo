package com.simonrowe.factory.cvefix.osv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.cvefix.config.CveRepairProperties;
import com.simonrowe.factory.cvefix.domain.MavenVersion;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OsvClientTest {

  private static final String COORDINATE = "org.apache.tomcat.embed:tomcat-embed-core";
  private final ObjectMapper mapper = new ObjectMapper();
  private final List<String> requests = new ArrayList<>();
  private final List<String> responses = new ArrayList<>();
  private int status = 200;
  private HttpServer server;
  private OsvClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/query", exchange -> {
      requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] out = responses.remove(0).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, out.length);
      exchange.getResponseBody().write(out);
      exchange.close();
    });
    server.start();
    client = new OsvClient(new CveRepairProperties(true, null,
        "http://127.0.0.1:" + server.getAddress().getPort(), null, null), mapper);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static final String FIXED_IN_26 = "[{\"introduced\":\"0\"},{\"fixed\":\"11.0.26\"}]";

  private static String page(final String vulns, final String rest) {
    return "{\"vulns\":[%s]%s}".formatted(vulns, rest);
  }

  private static String vuln(final String id, final String events) {
    return """
        {"id":"%s","affected":[{"package":{"ecosystem":"Maven","name":"%s"},
          "ranges":[{"type":"ECOSYSTEM","events":%s}]}]}""".formatted(id, COORDINATE, events);
  }

  @Test
  void asksForTheVersionAndReadsTheFixClosingItsRange() {
    responses.add(page(vuln("GHSA-a", """
        [{"introduced":"10.0.0"},{"fixed":"10.1.40"},{"introduced":"11.0.0"},{"fixed":"11.0.26"}]\
        """), ""));

    assertThat(client.advisories(COORDINATE, "11.0.25"))
        .containsExactly(new OsvClient.Advisory("GHSA-a", "11.0.26"));
    assertThat(requests.get(0)).contains("\"version\":\"11.0.25\"", COORDINATE, "\"Maven\"");
  }

  @Test
  void followsPageTokens() {
    responses.add(page(vuln("GHSA-a", FIXED_IN_26), ",\"next_page_token\":\"p2\""));
    responses.add(page(vuln("GHSA-b", FIXED_IN_26.replace("26", "27")), ""));

    assertThat(client.advisories(COORDINATE, "11.0.25")).extracting(OsvClient.Advisory::id)
        .containsExactly("GHSA-a", "GHSA-b");
    assertThat(requests.get(1)).contains("\"page_token\":\"p2\"");
  }

  @Test
  void errorIsFailureNotEmptyAnswer() {
    status = 503;
    responses.add("{}");

    assertThatThrownBy(() -> client.advisories(COORDINATE, "11.0.25"))
        .hasMessageContaining("503");
  }

  @Test
  void lastAffectedMeansNoFixAndOtherPackagesAreIgnored() throws Exception {
    MavenVersion current = MavenVersion.of("11.0.25");
    assertThat(OsvClient.fixedVersion(mapper.readTree(vuln("GHSA-a",
        "[{\"introduced\":\"0\"},{\"last_affected\":\"11.0.30\"}]")), COORDINATE, current))
        .isNull();
    assertThat(OsvClient.fixedVersion(mapper.readTree(vuln("GHSA-a",
        "[{\"introduced\":\"0\"},{\"fixed\":\"11.0.26\"}]")), "other:artifact", current))
        .isNull();
    // A range the version is not in says nothing about it.
    assertThat(OsvClient.fixedVersion(mapper.readTree(vuln("GHSA-a",
        "[{\"introduced\":\"9.0.0\"},{\"fixed\":\"9.0.90\"}]")), COORDINATE, current))
        .isNull();
  }
}
