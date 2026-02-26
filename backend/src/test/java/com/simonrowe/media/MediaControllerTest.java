package com.simonrowe.media;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.SharedMongoContainer;
import com.simonrowe.blog.BlogSearchRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "management.health.kafka.enabled=false",
    "management.health.elasticsearch.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.elasticsearch.uris=http://localhost:9200",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test.auth0.com/",
    "spring.security.oauth2.resourceserver.jwt.audiences=https://test-api",
    "uploads.path=target/test-uploads"
})
@AutoConfigureMockMvc
class MediaControllerTest {

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @MockitoBean
  private ElasticsearchOperations elasticsearchOperations;

  @MockitoBean
  private BlogSearchRepository blogSearchRepository;

  @MockitoBean
  private ImageVariantGenerator imageVariantGenerator;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MediaAssetRepository mediaAssetRepository;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    SharedMongoContainer.configureProperties(registry);
  }

  @BeforeEach
  void setup() throws Exception {
    mediaAssetRepository.deleteAll();
    when(imageVariantGenerator.generateVariants(any(), any(), any()))
        .thenReturn(Map.of());
  }

  @Test
  void uploadReturnsCreated() throws Exception {
    final MockMultipartFile file = new MockMultipartFile(
        "file",
        "test-image.jpg",
        MediaType.IMAGE_JPEG_VALUE,
        "fake-image-content".getBytes()
    );

    mockMvc.perform(multipart("/api/admin/media")
            .file(file)
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.fileName").value("test-image.jpg"))
        .andExpect(jsonPath("$.mimeType").value(MediaType.IMAGE_JPEG_VALUE));
  }

  @Test
  void uploadRejectsUnsupportedMimeType() throws Exception {
    final MockMultipartFile file = new MockMultipartFile(
        "file",
        "test-file.txt",
        MediaType.TEXT_PLAIN_VALUE,
        "plain text content".getBytes()
    );

    mockMvc.perform(multipart("/api/admin/media")
            .file(file)
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listMediaReturnsPage() throws Exception {
    mockMvc.perform(get("/api/admin/media")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void getByIdReturnsAsset() throws Exception {
    final MediaAsset asset = sampleAsset("asset-1", "photo.jpg",
        MediaType.IMAGE_JPEG_VALUE);
    mediaAssetRepository.save(asset);

    mockMvc.perform(get("/api/admin/media/asset-1")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("asset-1"))
        .andExpect(jsonPath("$.fileName").value("photo.jpg"))
        .andExpect(jsonPath("$.mimeType").value(MediaType.IMAGE_JPEG_VALUE));
  }

  @Test
  void getByIdReturnsNotFound() throws Exception {
    mockMvc.perform(get("/api/admin/media/nonexistent")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteRemovesAsset() throws Exception {
    final MediaAsset asset = sampleAsset("asset-1", "photo.jpg",
        MediaType.IMAGE_JPEG_VALUE);
    mediaAssetRepository.save(asset);

    mockMvc.perform(delete("/api/admin/media/asset-1")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void adminMediaEndpointsRequireAuth() throws Exception {
    mockMvc.perform(get("/api/admin/media"))
        .andExpect(status().isUnauthorized());
  }

  private static MediaAsset sampleAsset(
      final String id,
      final String fileName,
      final String mimeType
  ) {
    return new MediaAsset(
        id,
        fileName,
        mimeType,
        1024L,
        id + "/original.jpg",
        Map.of(),
        java.time.Instant.parse("2026-01-01T10:00:00Z"),
        java.time.Instant.parse("2026-01-01T10:00:00Z"),
        null
    );
  }
}
