package com.simonrowe;

import com.simonrowe.blog.BlogSearchRepository;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.media.ImageVariantGenerator;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
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

  @Autowired
  protected MockMvc mockMvc;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    SharedMongoContainer.configureProperties(registry);
  }
}
