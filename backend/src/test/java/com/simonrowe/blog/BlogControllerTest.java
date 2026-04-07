package com.simonrowe.blog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.SharedMongoContainer;
import com.simonrowe.media.MediaAsset;
import com.simonrowe.media.MediaAssetRepository;
import com.simonrowe.media.ImageVariantGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BlogControllerTest {

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @MockitoBean
  private ElasticsearchOperations elasticsearchOperations;

  @MockitoBean
  private VectorStore vectorStore;

  @MockitoBean
  private BlogSearchRepository blogSearchRepository;

  @MockitoBean
  private ImageVariantGenerator imageVariantGenerator;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private BlogRepository blogRepository;

  @Autowired
  private MediaAssetRepository mediaAssetRepository;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    SharedMongoContainer.configureProperties(registry);
  }

  @BeforeEach
  void setup() {
    blogRepository.deleteAll();
    mediaAssetRepository.deleteAll();
  }

  @Test
  void listPublishedBlogsReturnsOnlyPublishedBlogs() throws Exception {
    blogRepository.saveAll(List.of(
        sampleBlog("b-1", "Published Post", true),
        sampleBlog("b-2", "Draft Post", false)
    ));

    mockMvc.perform(get("/api/blogs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].title").value("Published Post"))
        .andExpect(jsonPath("$[0].id").value("b-1"));
  }

  @Test
  void listPublishedBlogsReturnsEmptyListWhenNonePublished() throws Exception {
    blogRepository.save(sampleBlog("b-1", "Draft", false));

    mockMvc.perform(get("/api/blogs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void getBlogByIdReturnsDetailForPublishedBlog() throws Exception {
    blogRepository.save(sampleBlog("b-1", "My Blog Post", true));

    mockMvc.perform(get("/api/blogs/b-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("b-1"))
        .andExpect(jsonPath("$.title").value("My Blog Post"))
        .andExpect(jsonPath("$.content").value("Full content here."));
  }

  @Test
  void listPublishedBlogsUsesSmallVariantWhenAvailable() throws Exception {
    blogRepository.save(sampleBlog("b-1", "Published Post", true));
    mediaAssetRepository.save(sampleMediaAsset(
        "/images/blogs/sample.jpg",
        "/uploads/asset-1/asset-1_small.jpg",
        "/uploads/asset-1/asset-1_large.jpg"));

    mockMvc.perform(get("/api/blogs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].featuredImageUrl")
            .value("/uploads/asset-1/asset-1_small.jpg"));
  }

  @Test
  void getBlogByIdUsesLargeVariantWhenAvailable() throws Exception {
    blogRepository.save(sampleBlog("b-1", "Published Post", true));
    mediaAssetRepository.save(sampleMediaAsset(
        "/images/blogs/sample.jpg",
        "/uploads/asset-1/asset-1_small.jpg",
        "/uploads/asset-1/asset-1_large.jpg"));

    mockMvc.perform(get("/api/blogs/b-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.featuredImageUrl")
            .value("/uploads/asset-1/asset-1_large.jpg"));
  }

  @Test
  void getBlogByIdReturnsNotFoundForUnpublishedBlog() throws Exception {
    blogRepository.save(sampleBlog("b-1", "Draft Post", false));

    mockMvc.perform(get("/api/blogs/b-1"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getBlogByIdReturnsNotFoundForMissingBlog() throws Exception {
    mockMvc.perform(get("/api/blogs/nonexistent"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getLatestBlogsReturnsRequestedNumberOfPosts() throws Exception {
    for (int i = 1; i <= 5; i++) {
      blogRepository.save(sampleBlog("b-" + i, "Post " + i, true));
    }

    mockMvc.perform(get("/api/blogs/latest?limit=3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  void getLatestBlogsUsesDefaultLimitOfThree() throws Exception {
    for (int i = 1; i <= 5; i++) {
      blogRepository.save(sampleBlog("b-" + i, "Post " + i, true));
    }

    mockMvc.perform(get("/api/blogs/latest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  private static Blog sampleBlog(final String id, final String title, final boolean published) {
    return new Blog(
        id,
        title,
        "Short description of " + title,
        "Full content here.",
        published,
        "/images/blogs/sample.jpg",
        Instant.parse("2024-06-01T10:00:00Z"),
        Instant.parse("2024-06-01T10:00:00Z"),
        null,
        null
    );
  }

  private static MediaAsset sampleMediaAsset(
      final String originalPath,
      final String smallPath,
      final String largePath
  ) {
    return new MediaAsset(
        "asset-1",
        "sample.jpg",
        "image/jpeg",
        1024L,
        originalPath,
        Map.of(
            "small", new MediaAsset.VariantInfo(smallPath, 300, 200, 512L),
            "large", new MediaAsset.VariantInfo(largePath, 1200, 800, 2048L)
        ),
        Instant.parse("2024-06-01T10:00:00Z"),
        Instant.parse("2024-06-01T10:00:00Z"),
        null
    );
  }
}
