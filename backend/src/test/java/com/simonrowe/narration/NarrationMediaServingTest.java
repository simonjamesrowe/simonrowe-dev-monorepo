package com.simonrowe.narration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class NarrationMediaServingTest extends AbstractIntegrationTest {

  private static final byte[] MP3 = new byte[]{'I', 'D', '3', 4, 5, 6, 7, 8};
  private static final Path FILE =
      Path.of("target/test-uploads/narrations/media-test/narration.mp3");

  @BeforeEach
  void createAudio() throws IOException {
    Files.createDirectories(FILE.getParent());
    Files.write(FILE, MP3);
  }

  @AfterEach
  void deleteAudio() throws IOException {
    Files.deleteIfExists(FILE);
    Files.deleteIfExists(FILE.getParent());
  }

  @Test
  void servesImmutablePublicMp3() throws Exception {
    mockMvc.perform(get("/uploads/narrations/media-test/narration.mp3"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("audio/mpeg")))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
            Matchers.allOf(Matchers.containsString("public"),
                Matchers.containsString("max-age=31536000"),
                Matchers.containsString("immutable"))))
        .andExpect(content().bytes(MP3));
  }

  @Test
  void supportsByteRangesForSeeking() throws Exception {
    mockMvc.perform(get("/uploads/narrations/media-test/narration.mp3")
            .header(HttpHeaders.RANGE, "bytes=2-5"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/8"))
        .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
        .andExpect(content().bytes(new byte[]{'3', 4, 5, 6}));
  }
}
