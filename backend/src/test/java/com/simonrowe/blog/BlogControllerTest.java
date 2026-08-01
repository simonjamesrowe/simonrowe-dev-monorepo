package com.simonrowe.blog;

import static com.simonrowe.blog.BlogContentType.DIGEST;
import static com.simonrowe.blog.BlogContentType.ENGINEERING;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.media.MediaAsset;
import com.simonrowe.media.MediaAssetRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BlogControllerTest extends AbstractIntegrationTest {

  private static final Instant ENGINEERING_CREATED = Instant.parse("2026-01-03T10:00:00Z");
  private static final Instant DIGEST_CREATED = Instant.parse("2026-02-02T10:00:00Z");

  @Autowired
  private BlogRepository blogRepository;

  @Autowired
  private MediaAssetRepository mediaAssetRepository;

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

  @Test
  void listPublishedBlogsIncludesContentType() throws Exception {
    blogRepository.saveAll(List.of(
        sampleBlog("b-1", "Engineering Post", true, ENGINEERING_CREATED, ENGINEERING),
        sampleBlog("b-2", "Digest Post", true, DIGEST_CREATED, DIGEST)
    ));

    mockMvc.perform(get("/api/blogs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value("b-2"))
        .andExpect(jsonPath("$[0].contentType").value("DIGEST"))
        .andExpect(jsonPath("$[1].id").value("b-1"))
        .andExpect(jsonPath("$[1].contentType").value("ENGINEERING"));
  }

  @Test
  void listPublishedBlogsCoercesStoredNullContentTypeToEngineering() throws Exception {
    blogRepository.save(sampleBlog("b-1", "Legacy Post", true, ENGINEERING_CREATED, null));

    mockMvc.perform(get("/api/blogs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].contentType").value("ENGINEERING"));
  }

  @Test
  void getLatestBlogsAppliesContentTypeFilterBeforeLimit() throws Exception {
    saveEngineeringPostsBelowNewerDigests();

    mockMvc.perform(get("/api/blogs/latest")
            .param("limit", "3")
            .param("contentType", "ENGINEERING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value("eng-3"))
        .andExpect(jsonPath("$[1].id").value("eng-2"))
        .andExpect(jsonPath("$[2].id").value("eng-1"))
        .andExpect(jsonPath("$[*].contentType")
            .value(everyItem(equalTo("ENGINEERING"))));
  }

  @Test
  void getLatestBlogsUnfilteredStillReturnsNewestOfAnyType() throws Exception {
    saveEngineeringPostsBelowNewerDigests();

    mockMvc.perform(get("/api/blogs/latest").param("limit", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value("dig-2"))
        .andExpect(jsonPath("$[1].id").value("dig-1"))
        .andExpect(jsonPath("$[2].id").value("eng-3"));
  }

  @Test
  void getLatestBlogsFiltersToDigestsOnly() throws Exception {
    saveEngineeringPostsBelowNewerDigests();

    mockMvc.perform(get("/api/blogs/latest")
            .param("limit", "3")
            .param("contentType", "DIGEST"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value("dig-2"))
        .andExpect(jsonPath("$[1].id").value("dig-1"));
  }

  @Test
  void getLatestBlogsRejectsUnknownContentType() throws Exception {
    mockMvc.perform(get("/api/blogs/latest").param("contentType", "NONSENSE"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getLatestBlogsRejectsLimitBelowMinimum() throws Exception {
    mockMvc.perform(get("/api/blogs/latest").param("limit", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getLatestBlogsRejectsLimitAboveMaximum() throws Exception {
    mockMvc.perform(get("/api/blogs/latest").param("limit", "11"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getBlogByIdIncludesContentType() throws Exception {
    blogRepository.save(sampleBlog("b-1", "Digest Post", true, DIGEST_CREATED, DIGEST));

    mockMvc.perform(get("/api/blogs/b-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contentType").value("DIGEST"));
  }

  /**
   * Three engineering posts with two <em>newer</em> digests above them, so a limit applied
   * before the content-type filter would return fewer than three engineering posts.
   */
  private void saveEngineeringPostsBelowNewerDigests() {
    blogRepository.saveAll(List.of(
        sampleBlog("eng-1", "Engineering One", true,
            Instant.parse("2026-01-01T10:00:00Z"), ENGINEERING),
        sampleBlog("eng-2", "Engineering Two", true,
            Instant.parse("2026-01-02T10:00:00Z"), ENGINEERING),
        sampleBlog("eng-3", "Engineering Three", true,
            Instant.parse("2026-01-03T10:00:00Z"), ENGINEERING),
        sampleBlog("dig-1", "Digest One", true,
            Instant.parse("2026-02-01T10:00:00Z"), DIGEST),
        sampleBlog("dig-2", "Digest Two", true,
            Instant.parse("2026-02-02T10:00:00Z"), DIGEST)
    ));
  }

  private static Blog sampleBlog(final String id, final String title, final boolean published) {
    return sampleBlog(
        id,
        title,
        published,
        Instant.parse("2024-06-01T10:00:00Z"),
        BlogContentType.ENGINEERING);
  }

  private static Blog sampleBlog(
      final String id,
      final String title,
      final boolean published,
      final Instant createdDate,
      final BlogContentType contentType
  ) {
    return new Blog(
        id,
        title,
        "Short description of " + title,
        "Full content here.",
        published,
        "/images/blogs/sample.jpg",
        createdDate,
        createdDate,
        null,
        null,
        contentType
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
