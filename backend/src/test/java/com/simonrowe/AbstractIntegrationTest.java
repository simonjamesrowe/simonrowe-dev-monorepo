package com.simonrowe;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.openai.OpenAiCompatibleModelFactory;
import com.simonrowe.blog.BlogSearchRepository;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.media.ImageVariantGenerator;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(AbstractIntegrationTest.OpenAiCompatibleModelFactoryTestConfig.class)
public abstract class AbstractIntegrationTest {

  @MockitoBean
  protected JwtDecoder jwtDecoder;

  @MockitoBean
  protected ElasticsearchOperations elasticsearchOperations;

  @MockitoBean
  protected VectorStore vectorStore;

  @MockitoBean
  protected BlogSearchRepository blogSearchRepository;

  @MockitoBean
  protected ImageVariantGenerator imageVariantGenerator;

  @MockitoBean
  protected ContentChangePublisher contentChangePublisher;

  @MockitoBean
  protected Ai ai;

  @Autowired
  protected MockMvc mockMvc;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    SharedMongoContainer.configureProperties(registry);
    SharedKafkaContainer.configureProperties(registry);
  }

  /**
   * Supplies a real (network-free) {@link OpenAiCompatibleModelFactory} for the test
   * profile, which excludes Embabel's {@code AgentOpenAiAutoConfiguration} (so
   * ordinary tests never touch OpenAI or need a real API key). Without this,
   * {@code AgentConfig#gpt56LunaLlm} — a plain, unconditional bean depending on
   * this factory — would fail to construct in every {@code @SpringBootTest},
   * since the factory would otherwise never exist in the test context. Building
   * the client objects here does not perform any network I/O.
   */
  @TestConfiguration
  static class OpenAiCompatibleModelFactoryTestConfig {

    @Bean
    OpenAiCompatibleModelFactory openAiCompatibleModelFactory() {
      return new OpenAiCompatibleModelFactory(
          null,
          "test-dummy-key",
          null,
          null,
          Map.of(),
          ObservationRegistry.NOOP,
          new ObjectProvider<RestClient.Builder>() {
            @Override
            public RestClient.Builder getObject() {
              return RestClient.builder();
            }
          },
          new ObjectProvider<WebClient.Builder>() {
            @Override
            public WebClient.Builder getObject() {
              return WebClient.builder();
            }
          });
    }
  }
}
