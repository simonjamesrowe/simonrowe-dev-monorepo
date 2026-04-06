package com.simonrowe.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.SharedMongoContainer;
import com.simonrowe.blog.BlogSearchRepository;
import com.simonrowe.media.ImageVariantGenerator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminBlogControllerTest {

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
  private AdminBlogRepository adminBlogRepository;

  @Autowired
  private AdminTagRepository adminTagRepository;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    SharedMongoContainer.configureProperties(registry);
  }

  @BeforeEach
  void setup() {
    adminBlogRepository.deleteAll();
    adminTagRepository.deleteAll();
  }

  @Test
  void listBlogsReturnsPaginatedResults() throws Exception {
    adminBlogRepository.saveAll(List.of(
        sampleBlog("b-1", "First Post", false),
        sampleBlog("b-2", "Second Post", true)
    ));

    mockMvc.perform(get("/api/admin/blogs")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void createBlogReturnsCreated() throws Exception {
    String body = """
        {
            "title": "New Blog Post",
            "shortDescription": "A short description of the post",
            "content": "Full content here",
            "published": false,
            "tags": [],
            "skills": []
        }
        """;

    mockMvc.perform(post("/api/admin/blogs")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value("New Blog Post"))
        .andExpect(jsonPath("$.shortDescription").value("A short description of the post"))
        .andExpect(jsonPath("$.published").value(false))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  void createBlogValidatesRequiredFields() throws Exception {
    String body = """
        {
            "shortDescription": "A short description",
            "content": "Full content"
        }
        """;

    mockMvc.perform(post("/api/admin/blogs")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getBlogByIdReturnsBlog() throws Exception {
    adminBlogRepository.save(sampleBlog("b-1", "My Blog Post", true));

    mockMvc.perform(get("/api/admin/blogs/b-1")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("b-1"))
        .andExpect(jsonPath("$.title").value("My Blog Post"))
        .andExpect(jsonPath("$.tags.length()").value(1))
        .andExpect(jsonPath("$.tags[0]").isString());
  }

  @Test
  void getBlogByIdReturnsNotFound() throws Exception {
    mockMvc.perform(get("/api/admin/blogs/nonexistent")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateBlogReturnsUpdated() throws Exception {
    adminBlogRepository.save(sampleBlog("b-1", "Original Title", false));

    String body = """
        {
            "title": "Updated Title",
            "shortDescription": "Updated short description",
            "content": "Updated content",
            "published": false,
            "tags": [],
            "skills": []
        }
        """;

    mockMvc.perform(put("/api/admin/blogs/b-1")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("b-1"))
        .andExpect(jsonPath("$.title").value("Updated Title"))
        .andExpect(jsonPath("$.shortDescription").value("Updated short description"));
  }

  @Test
  void deleteBlogReturnsNoContent() throws Exception {
    adminBlogRepository.save(sampleBlog("b-1", "Post To Delete", false));

    mockMvc.perform(delete("/api/admin/blogs/b-1")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  private Blog sampleBlog(final String id, final String title, final boolean published) {
    Instant now = Instant.parse("2024-06-01T10:00:00Z");
    Tag tag = adminTagRepository.save(new Tag(null, "java-" + id, now, now, null));
    return new Blog(
        id,
        title,
        "Short description of " + title,
        "Full content here.",
        published,
        null,
        List.of(tag),
        List.of(),
        now,
        now,
        null
    );
  }
}
