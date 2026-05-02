package com.simonrowe.search.elasticsearch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.cluster.ElasticsearchClusterClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.PutIndicesSettingsResponse;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import java.io.IOException;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElasticsearchConfigTest {

  private ElasticsearchClient esClient;
  private ElasticsearchIndicesClient indicesClient;
  private ElasticsearchClusterClient clusterClient;
  private ElasticsearchConfig config;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() throws IOException {
    esClient = mock(ElasticsearchClient.class);
    indicesClient = mock(ElasticsearchIndicesClient.class);
    clusterClient = mock(ElasticsearchClusterClient.class);
    when(esClient.indices()).thenReturn(indicesClient);
    when(esClient.cluster()).thenReturn(clusterClient);
    HealthResponse health = mock(HealthResponse.class);
    when(health.status()).thenReturn(HealthStatus.Green);
    when(health.timedOut()).thenReturn(false);
    when(clusterClient.health(any(Function.class))).thenReturn(health);
    PutIndicesSettingsResponse putResponse = mock(PutIndicesSettingsResponse.class);
    when(indicesClient.putSettings(any(Function.class))).thenReturn(putResponse);
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

    // Should call create twice: once for site_search, once for blog_search.
    // content-embeddings is created by Spring AI so we just adjust settings if it exists.
    verify(indicesClient, times(2)).create(any(Function.class));
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

  @SuppressWarnings("unchecked")
  @Test
  void pinsContentEmbeddingsReplicasToZeroWhenIndexExists() throws Exception {
    when(indicesClient.exists(any(Function.class)))
        .thenReturn(new BooleanResponse(true));

    config.createIndicesOnStartup();

    verify(indicesClient, atLeastOnce()).putSettings(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void skipsReplicaPinWhenContentEmbeddingsAbsent() throws Exception {
    when(indicesClient.exists(any(Function.class)))
        .thenReturn(new BooleanResponse(false));
    CreateIndexResponse createResponse = mock(CreateIndexResponse.class);
    when(indicesClient.create(any(Function.class))).thenReturn(createResponse);

    config.createIndicesOnStartup();

    verify(indicesClient, never()).putSettings(any(Function.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void healthCheckSurvivesIoException() throws Exception {
    when(indicesClient.exists(any(Function.class)))
        .thenReturn(new BooleanResponse(true));
    when(clusterClient.health(any(Function.class)))
        .thenThrow(new IOException("ES unreachable"));

    // Should not propagate
    config.createIndicesOnStartup();

    verify(clusterClient).health(any(Function.class));
  }
}
