package com.simonrowe.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.search.elasticsearch.ElasticsearchJsonpMapperConfig;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The embeddings backup against a real Elasticsearch, through the client the application builds.
 *
 * <p>SIM-76: since the Boot 4 upgrade every export failed with
 * {@code [es/search] Failed to decode response}, because hits were decoded into Jackson 3's
 * {@code JsonNode} by a Jackson 2 mapper. {@code BackupService} logs that and carries on, so the
 * nightly archive was written without its vectors and still reported success. Only a real
 * response through the real {@code JacksonJsonpMapper} reproduces it; a stubbed client cannot.
 */
@Testcontainers
class ElasticsearchBackupServiceIntegrationTest {

  @Container
  static ElasticsearchContainer elasticsearch =
      new ElasticsearchContainer("elasticsearch:9.4.5")
          .withEnv("xpack.security.enabled", "false");

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Rest5Client lowLevel;
  private static ElasticsearchBackupService service;

  @BeforeAll
  static void createClient() {
    lowLevel = Rest5Client.builder(URI.create("http://" + elasticsearch.getHttpHostAddress()))
        .build();
    ElasticsearchClient client = new ElasticsearchClient(new Rest5ClientTransport(
        lowLevel, new ElasticsearchJsonpMapperConfig().jacksonJsonpMapper()));
    service = new ElasticsearchBackupService(client, "content-embeddings", "school-embeddings");
  }

  @AfterAll
  static void closeClient() throws Exception {
    lowLevel.close();
  }

  private static void put(final String index, final String id, final String body)
      throws Exception {
    Request request = new Request("PUT", "/" + index + "/_doc/" + id + "?refresh=true");
    request.setJsonEntity(body);
    lowLevel.performRequest(request);
  }

  @Test
  @DisplayName("an exported index imports into an empty one with every field intact")
  void roundTripsAnIndex() throws Exception {
    final String source = "backup-source-" + UUID.randomUUID();
    final String target = "backup-target-" + UUID.randomUUID();
    String first = """
        {"content":"first chunk","embedding":[0.25,-0.5,0.75],\
        "metadata":{"visibility":"PUBLIC","yearGroups":["Year 3"]}}""";
    String second = """
        {"content":"second chunk","embedding":[1.0,0.0,-1.0],"metadata":{"title":"Notes"}}""";
    put(source, "doc-1", first);
    put(source, "doc-2", second);

    JsonNode exported = JSON.readTree(service.exportEmbeddings(source));

    assertThat(exported).hasSize(2);

    int imported = service.importEmbeddings(target, service.exportEmbeddings(source));
    lowLevel.performRequest(new Request("POST", "/" + target + "/_refresh"));

    assertThat(imported).isEqualTo(2);
    assertThat(sourceOf(target, "doc-1")).isEqualTo(JSON.readTree(first));
    assertThat(sourceOf(target, "doc-2")).isEqualTo(JSON.readTree(second));
  }

  @Test
  @DisplayName("an index larger than one scroll page exports every document")
  void exportsAcrossScrollPages() throws Exception {
    String index = "backup-paged-" + UUID.randomUUID();
    StringBuilder bulk = new StringBuilder();
    int total = 1_203;
    for (int i = 0; i < total; i++) {
      bulk.append("""
          {"index":{"_id":"d%d"}}
          {"content":"chunk %d","embedding":[%d.0]}
          """.formatted(i, i, i));
    }
    Request request = new Request("POST", "/" + index + "/_bulk?refresh=true");
    request.setJsonEntity(bulk.toString());
    lowLevel.performRequest(request);

    assertThat(JSON.readTree(service.exportEmbeddings(index))).hasSize(total);
  }

  @Test
  @DisplayName("a missing index exports as an empty array rather than failing the backup")
  void missingIndexIsEmpty() throws Exception {
    assertThat(service.exportEmbeddings("absent-" + UUID.randomUUID())).isEqualTo("[]");
  }

  private static JsonNode sourceOf(final String index, final String id) throws Exception {
    var response = lowLevel.performRequest(new Request("GET", "/" + index + "/_doc/" + id));
    return JSON.readTree(response.getEntity().getContent()).get("_source");
  }
}
