package com.simonrowe.search.elasticsearch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import java.io.IOException;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElasticsearchConfigTest {

  private ElasticsearchClient esClient;
  private ElasticsearchIndicesClient indicesClient;
  private ElasticsearchConfig config;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    esClient = mock(ElasticsearchClient.class);
    indicesClient = mock(ElasticsearchIndicesClient.class);
    when(esClient.indices()).thenReturn(indicesClient);
    config = new ElasticsearchConfig(esClient);
  }

  @SuppressWarnings("unchecked")
  @Test
  void createIndicesOnStartupCreatesIndicesWhenNotExist() throws Exception {
    when(indicesClient.exists(any(Function.class)))
        .thenReturn(new BooleanResponse(false));
    CreateIndexResponse createResponse = mock(CreateIndexResponse.class);
    when(indicesClient.create(any(Function.class))).thenReturn(createResponse);

    config.createIndicesOnStartup();

    // Should call create twice: once for site_search, once for blog_search
    verify(indicesClient, org.mockito.Mockito.times(2))
        .create(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void createIndicesOnStartupSkipsWhenAlreadyExist() throws Exception {
    when(indicesClient.exists(any(Function.class)))
        .thenReturn(new BooleanResponse(true));

    config.createIndicesOnStartup();

    verify(indicesClient, never()).create(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void createIndicesHandlesIoException() throws Exception {
    when(indicesClient.exists(any(Function.class)))
        .thenThrow(new IOException("Connection refused"));

    config.createIndicesOnStartup();

    verify(indicesClient, never()).create(any(Function.class));
  }
}
