package com.simonrowe.search.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import java.io.IOException;
import java.util.Map;
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

  private final ElasticsearchClient client;

  public ElasticsearchConfig(final ElasticsearchClient client) {
    this.client = client;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(1)
  public void createIndicesOnStartup() {
    createSiteSearchIndex();
    createBlogSearchIndex();
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
              .properties("image", Property.of(p -> p
                  .keyword(KeywordProperty.of(k -> k.index(false)))))
              .properties("url", Property.of(p -> p
                  .keyword(KeywordProperty.of(k -> k.index(false)))))));
      LOG.info("Created index {}", SITE_SEARCH_INDEX);
    } catch (IOException e) {
      LOG.error("Failed to create index {}", SITE_SEARCH_INDEX, e);
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
