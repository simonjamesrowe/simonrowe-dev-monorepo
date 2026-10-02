package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.drive.Drive;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A Drive download that loses its connection part-way through a chunk resumes from the last byte
 * it wrote, and the file comes out byte-for-byte identical.
 */
class GoogleDriveDownloadResumeTest {

  private static final int CHUNK = 10 * 1024 * 1024;

  /** Serves ranged chunks of {@code file}; each entry in {@code cuts} breaks one response. */
  private static final class FlakyDrive extends MockHttpTransport {

    private final byte[] file;
    private final List<Integer> cuts;
    final List<String> ranges = new ArrayList<>();

    FlakyDrive(final byte[] file, final List<Integer> cuts) {
      this.file = file;
      this.cuts = new ArrayList<>(cuts);
    }

    @Override
    public LowLevelHttpRequest buildRequest(final String method, final String url) {
      return new MockLowLevelHttpRequest(url) {
        @Override
        public LowLevelHttpResponse execute() {
          String range = getFirstHeaderValue("Range");
          ranges.add(range);
          String[] bounds = range.substring("bytes=".length()).split("-");
          int start = Integer.parseInt(bounds[0]);
          int end = Math.min(Integer.parseInt(bounds[1]), file.length - 1);
          byte[] body = Arrays.copyOfRange(file, start, end + 1);
          InputStream content = new ByteArrayInputStream(body);
          if (!cuts.isEmpty()) {
            content = breaksAfter(content, cuts.remove(0));
          }
          return new MockLowLevelHttpResponse()
              .setStatusCode(206)
              .addHeader("Content-Range", "bytes " + start + "-" + end + "/" + file.length)
              .setContentLength(body.length)
              .setContent(content);
        }
      };
    }
  }

  private static InputStream breaksAfter(final InputStream in, final int bytes) {
    return new InputStream() {
      private int read;

      @Override
      public int read() throws IOException {
        if (read >= bytes) {
          throw new IOException("Connection has closed: connection reset");
        }
        read++;
        return in.read();
      }

      @Override
      public int read(final byte[] b, final int off, final int len) throws IOException {
        if (read >= bytes) {
          throw new IOException("Connection has closed: connection reset");
        }
        int n = in.read(b, off, Math.min(len, bytes - read));
        if (n > 0) {
          read += n;
        }
        return n;
      }
    };
  }

  private static GoogleDriveService service(final MockHttpTransport transport) {
    Drive drive = new Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
        .setApplicationName("test").build();
    GoogleDriveService service = new GoogleDriveService(drive, "folder", "");
    service.setRetryDelay(Duration.ZERO);
    return service;
  }

  private static byte[] file(final int size) {
    byte[] bytes = new byte[size];
    new Random(42).nextBytes(bytes);
    return bytes;
  }

  @Test
  void resumesFromTheLastByteWrittenAfterResetsMidChunk() throws IOException {
    byte[] file = file(CHUNK * 2 + 1234);
    // The first chunk breaks after 3 MB; the resumed request then breaks once more, 1 MB in.
    FlakyDrive transport = new FlakyDrive(file, List.of(3 * 1024 * 1024, 1024 * 1024));
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    service(transport).downloadFile("file-id", out);

    assertThat(out.toByteArray()).isEqualTo(file);
    assertThat(transport.ranges.get(0)).isEqualTo("bytes=0-" + (CHUNK - 1));
    assertThat(transport.ranges.get(1)).startsWith("bytes=" + (3 * 1024 * 1024) + "-");
    assertThat(transport.ranges.get(2)).startsWith("bytes=" + (4 * 1024 * 1024) + "-");
  }

  @Test
  void givesUpAfterTheLastAttempt() {
    List<Integer> everyResponseBreaks = new ArrayList<>();
    for (int i = 0; i < GoogleDriveService.DOWNLOAD_ATTEMPTS; i++) {
      everyResponseBreaks.add(1024);
    }
    FlakyDrive transport = new FlakyDrive(file(CHUNK), everyResponseBreaks);

    GoogleDriveService service = service(transport);

    assertThatThrownBy(() -> service.downloadFile("file-id", new ByteArrayOutputStream()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("connection reset");
    assertThat(transport.ranges).hasSize(GoogleDriveService.DOWNLOAD_ATTEMPTS);
  }
}
