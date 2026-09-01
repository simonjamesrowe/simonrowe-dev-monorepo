package com.simonrowe.embedding;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.ScrollResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.StringReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ElasticsearchBackupService {

  private static final Logger LOG =
      LoggerFactory.getLogger(ElasticsearchBackupService.class);
  private static final int SCROLL_SIZE = 500;
  private static final String SCROLL_TIMEOUT = "2m";

  private final ElasticsearchClient esClient;
  private final ObjectMapper objectMapper;
  private final String indexName;

  public ElasticsearchBackupService(
      final ElasticsearchClient esClient,
      @Value("${spring.ai.vectorstore.elasticsearch.index-name:content-embeddings}")
      final String indexName
  ) {
    this.esClient = esClient;
    this.objectMapper = new ObjectMapper();
    this.indexName = indexName;
  }

  public String exportEmbeddings() throws IOException {
    if (!esClient.indices().exists(e -> e.index(indexName)).value()) {
      LOG.info("Index {} does not exist, nothing to export", indexName);
      return "[]";
    }

    ArrayNode documents = objectMapper.createArrayNode();

    SearchResponse<JsonNode> searchResponse = esClient.search(s -> s
            .index(indexName)
            .size(SCROLL_SIZE)
            .scroll(t -> t.time(SCROLL_TIMEOUT)),
        JsonNode.class);

    String scrollId = searchResponse.scrollId();
    addHitsToArray(searchResponse, documents);

    while (searchResponse.hits().hits().size() > 0) {
      final String currentScrollId = scrollId;
      ScrollResponse<JsonNode> scrollResponse = esClient.scroll(s -> s
              .scrollId(currentScrollId)
              .scroll(t -> t.time(SCROLL_TIMEOUT)),
          JsonNode.class);
      scrollId = scrollResponse.scrollId();

      if (scrollResponse.hits().hits().isEmpty()) {
        break;
      }
      for (Hit<JsonNode> hit : scrollResponse.hits().hits()) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("_id", hit.id());
        doc.set("_source", hit.source());
        documents.add(doc);
      }
    }

    if (scrollId != null) {
      final String finalScrollId = scrollId;
      try {
        esClient.clearScroll(c -> c.scrollId(finalScrollId));
      } catch (Exception ex) {
        LOG.debug("Failed to clear scroll: {}", ex.getMessage());
      }
    }

    LOG.info("Exported {} embedding documents from index {}",
        documents.size(), indexName);
    return objectMapper.writerWithDefaultPrettyPrinter()
        .writeValueAsString(documents);
  }

  public int importEmbeddings(final String jsonContent) throws IOException {
    ArrayNode documents = (ArrayNode) objectMapper.readTree(jsonContent);
    if (documents.isEmpty()) {
      LOG.info("No embedding documents to import");
      return 0;
    }

    if (esClient.indices().exists(e -> e.index(indexName)).value()) {
      esClient.indices().delete(d -> d.index(indexName));
      LOG.info("Deleted existing index {} before import", indexName);
    }

    int imported = 0;
    BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
    int batchCount = 0;

    for (JsonNode doc : documents) {
      String id = doc.get("_id").asText();
      JsonNode source = doc.get("_source");

      bulkBuilder.operations(op -> op
          .index(idx -> idx
              .index(indexName)
              .id(id)
              .document(source)
          )
      );
      batchCount++;

      if (batchCount >= SCROLL_SIZE) {
        BulkResponse response = esClient.bulk(bulkBuilder.build());
        if (response.errors()) {
          LOG.warn("Bulk import had errors");
        }
        imported += batchCount;
        bulkBuilder = new BulkRequest.Builder();
        batchCount = 0;
      }
    }

    if (batchCount > 0) {
      BulkResponse response = esClient.bulk(bulkBuilder.build());
      if (response.errors()) {
        LOG.warn("Bulk import had errors");
      }
      imported += batchCount;
    }

    LOG.info("Imported {} embedding documents into index {}",
        imported, indexName);
    return imported;
  }

  private void addHitsToArray(final SearchResponse<JsonNode> response,
      final ArrayNode documents) {
    for (Hit<JsonNode> hit : response.hits().hits()) {
      ObjectNode doc = objectMapper.createObjectNode();
      doc.put("_id", hit.id());
      doc.set("_source", hit.source());
      documents.add(doc);
    }
  }
}
