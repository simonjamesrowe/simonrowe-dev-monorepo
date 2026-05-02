package com.simonrowe.search.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

@Configuration
public class ElasticsearchConfig {

  private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchConfig.class);

  public static final String SITE_SEARCH_INDEX = "site_search";
  public static final String BLOG_SEARCH_INDEX = "blog_search";
  public static final String CONTENT_EMBEDDINGS_INDEX = "content-embeddings";

  private final ElasticsearchClient client;

  public ElasticsearchConfig(final ElasticsearchClient client) {
    this.client = client;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(1)
  public void createIndicesOnStartup() {
    createSiteSearchIndex();
    createBlogSearchIndex();
    pinContentEmbeddingsReplicasToZero();
    checkContentEmbeddingsHealth();
  }

  private void createSiteSearchIndex() {
    try {
      boolean exists = client.indices().exists(e -> e.index(SITE_SEARCH_INDEX)).value();
      if (exists) {
        LOG.info("Index {} already exists", SITE_SEARCH_INDEX);
        return;
      }
      client.indices().create(c -> c
          .index(SITE_SEARCH_INDEX)
          .settings(IndexSettings.of(s -> s
              .numberOfShards("1")
              .numberOfReplicas("0")))
          .mappings(m -> m
              .properties("name", Property.of(p -> p
                  .text(TextProperty.of(t -> t
                      .analyzer("standard")
                      .fields("keyword", Property.of(kp -> kp
                          .keyword(KeywordProperty.of(k -> k))))))))
              .properties("type", Property.of(p -> p
                  .keyword(KeywordProperty.of(k -> k))))
              .properties("shortDescription", Property.of(p -> p
                  .text(TextProperty.of(t -> t.analyzer("standard")))))
              .properties("longDescription", Property.of(p -> p
                  .text(TextProperty.of(t -> t.analyzer("standard")))))
              .properties("company", Property.of(p -> p
                  .text(TextProperty.of(t -> t.analyzer("standard")))))
              .properties("image", Property.of(p -> p
                  .keyword(KeywordProperty.of(k -> k.index(false)))))
              .properties("url", Property.of(p -> p
                  .keyword(KeywordProperty.of(k -> k.index(false)))))
              .properties("sortDate", Property.of(p -> p
                  .date(d -> d.format("strict_date_optional_time"))))));
      LOG.info("Created index {}", SITE_SEARCH_INDEX);
    } catch (IOException e) {
      LOG.error("Failed to create index {}", SITE_SEARCH_INDEX, e);
    }
  }

  private void pinContentEmbeddingsReplicasToZero() {
    try {
      boolean exists = client.indices().exists(e -> e.index(CONTENT_EMBEDDINGS_INDEX)).value();
      if (!exists) {
        LOG.info("Index {} does not exist yet; skipping replica settings update",
            CONTENT_EMBEDDINGS_INDEX);
        return;
      }
      client.indices().putSettings(p -> p
          .index(CONTENT_EMBEDDINGS_INDEX)
          .settings(IndexSettings.of(s -> s.numberOfReplicas("0"))));
      LOG.info("Pinned {} replicas to 0", CONTENT_EMBEDDINGS_INDEX);
    } catch (IOException e) {
      LOG.error("Failed to pin {} replicas to 0", CONTENT_EMBEDDINGS_INDEX, e);
    }
  }

  private void checkContentEmbeddingsHealth() {
    try {
      HealthResponse health = client.cluster().health(h -> h
          .index(CONTENT_EMBEDDINGS_INDEX)
          .waitForStatus(HealthStatus.Yellow)
          .timeout(Time.of(t -> t.time("30s"))));
      if (health.timedOut() || health.status() == HealthStatus.Red) {
        LOG.error("Vector index {} is RED at startup — RAG-backed chat will fail until "
            + "the index is repaired (delete + reembed). Cluster health: status={}, "
            + "active_primary_shards={}, unassigned_shards={}",
            CONTENT_EMBEDDINGS_INDEX, health.status(),
            health.activePrimaryShards(), health.unassignedShards());
      } else {
        LOG.info("Vector index {} health OK: status={}, active_primary_shards={}",
            CONTENT_EMBEDDINGS_INDEX, health.status(), health.activePrimaryShards());
      }
    } catch (IOException e) {
      LOG.error("Failed to query cluster health for {}", CONTENT_EMBEDDINGS_INDEX, e);
    }
  }

  private void createBlogSearchIndex() {
    try {
      boolean exists = client.indices().exists(e -> e.index(BLOG_SEARCH_INDEX)).value();
      if (exists) {
        LOG.info("Index {} already exists", BLOG_SEARCH_INDEX);
        return;
      }
      client.indices().create(c -> c
          .index(BLOG_SEARCH_INDEX)
          .settings(IndexSettings.of(s -> s
              .numberOfShards("1")
              .numberOfReplicas("0")))
          .mappings(m -> m
              .properties("title", Property.of(p -> p
                  .text(TextProperty.of(t -> t
                      .analyzer("standard")
                      .fields("keyword", Property.of(kp -> kp
                          .keyword(KeywordProperty.of(k -> k))))))))
              .properties("shortDescription", Property.of(p -> p
                  .text(TextProperty.of(t -> t.analyzer("standard")))))
              .properties("content", Property.of(p -> p
                  .text(TextProperty.of(t -> t.analyzer("standard")))))
              .properties("tags", Property.of(p -> p
                  .keyword(KeywordProperty.of(k -> k))))
              .properties("skills", Property.of(p -> p
                  .keyword(KeywordProperty.of(k -> k))))
              .properties("image", Property.of(p -> p
                  .keyword(KeywordProperty.of(k -> k.index(false)))))
              .properties("publishedDate", Property.of(p -> p
                  .date(d -> d.format("strict_date_optional_time"))))
              .properties("url", Property.of(p -> p
                  .keyword(KeywordProperty.of(k -> k.index(false)))))));
      LOG.info("Created index {}", BLOG_SEARCH_INDEX);
    } catch (IOException e) {
      LOG.error("Failed to create index {}", BLOG_SEARCH_INDEX, e);
    }
  }
}
