package com.simonrowe.media;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class MediaControllerTest extends AbstractIntegrationTest {

  @Autowired
  private MediaAssetRepository mediaAssetRepository;

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
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.fileName").value("test-image.jpg"))
        .andExpect(jsonPath("$.mimeType").value(MediaType.IMAGE_JPEG_VALUE));
  }

  @Test
  void uploadKeepsLegitimateExtension() throws Exception {
    final MockMultipartFile file = new MockMultipartFile(
        "file",
        "diagram.PNG",
        MediaType.IMAGE_PNG_VALUE,
        "fake-image-content".getBytes()
    );

    mockMvc.perform(multipart("/api/admin/media")
            .file(file)
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.originalPath").value(endsWith("/original.png")));
  }

  // The stored path is built from the client-supplied name, so a name carrying path
  // syntax must not reach it. Spring already strips separators and the "original."
  // prefix stops Path.resolve treating the value as absolute, but the service must not
  // depend on either — see MediaService.getExtension (Sonar javasecurity:S2083).
  @Test
  void uploadDiscardsAnExtensionCarryingPathSyntax() throws Exception {
    final MockMultipartFile file = new MockMultipartFile(
        "file",
        "evil.jpg/../../../../tmp/pwned",
        MediaType.IMAGE_JPEG_VALUE,
        "fake-image-content".getBytes()
    );

    mockMvc.perform(multipart("/api/admin/media")
            .file(file)
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isCreated())
        // Falls back to the extension implied by the validated MIME type.
        .andExpect(jsonPath("$.originalPath").value(endsWith("/original.jpg")))
        .andExpect(jsonPath("$.originalPath").value(not(containsString(".."))));
  }

  @Test
  void uploadFallsBackToTheMimeTypeWhenTheNameHasNoExtension() throws Exception {
    final MockMultipartFile file = new MockMultipartFile(
        "file",
        "screenshot",
        "image/webp",
        "fake-image-content".getBytes()
    );

    mockMvc.perform(multipart("/api/admin/media")
            .file(file)
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.originalPath").value(endsWith("/original.webp")));
  }

  /**
   * Video and captions are library items too, so a page's demo is CMS content. Neither is resized,
   * and nor is WebP: the JVM has no WebP reader or writer, so resizing one used to fail the upload
   * with a 500. The generator is mocked here, so what is asserted is which types reach it.
   */
  @Test
  void storesVideoCaptionsAndWebpAsOriginalsAndResizesOnlyWhatItCan() throws Exception {
    for (String[] upload : new String[][] {
        {"demo.mp4", "video/mp4"}, {"demo.vtt", "text/vtt"}, {"shot.webp", "image/webp"}}) {
      clearInvocations(imageVariantGenerator);
      mockMvc.perform(multipart("/api/admin/media")
              .file(new MockMultipartFile("file", upload[0], upload[1], "content".getBytes()))
              .with(adminJwt().jwt(j -> j.subject("test-user"))))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.mimeType").value(upload[1]))
          .andExpect(jsonPath("$.originalPath").value(endsWith("/original."
              + upload[0].substring(upload[0].lastIndexOf('.') + 1))));
      verify(imageVariantGenerator, never()).generateVariants(any(), any(), any());
    }

    clearInvocations(imageVariantGenerator);
    mockMvc.perform(multipart("/api/admin/media")
            .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", "content".getBytes()))
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isCreated());
    verify(imageVariantGenerator).generateVariants(any(), any(), any());
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
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listMediaReturnsPage() throws Exception {
    mockMvc.perform(get("/api/admin/media")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
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
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("asset-1"))
        .andExpect(jsonPath("$.fileName").value("photo.jpg"))
        .andExpect(jsonPath("$.mimeType").value(MediaType.IMAGE_JPEG_VALUE));
  }

  @Test
  void getByIdReturnsNotFound() throws Exception {
    mockMvc.perform(get("/api/admin/media/nonexistent")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteRemovesAsset() throws Exception {
    final MediaAsset asset = sampleAsset("asset-1", "photo.jpg",
        MediaType.IMAGE_JPEG_VALUE);
    mediaAssetRepository.save(asset);

    mockMvc.perform(delete("/api/admin/media/asset-1")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
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
        "/uploads/" + id + "/original.jpg",
        Map.of(),
        java.time.Instant.parse("2026-01-01T10:00:00Z"),
        java.time.Instant.parse("2026-01-01T10:00:00Z"),
        null
    );
  }
}
