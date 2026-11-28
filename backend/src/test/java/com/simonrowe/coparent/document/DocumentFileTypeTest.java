package com.simonrowe.coparent.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** A file is what its bytes say, never what its name or Content-Type claim. */
class DocumentFileTypeTest {

  static Stream<Arguments> knownTypes() {
    return Stream.of(
        Arguments.of(DocumentTestFiles.jpeg(), "image/jpeg"),
        Arguments.of(DocumentTestFiles.png(), "image/png"),
        Arguments.of(DocumentTestFiles.webp(), "image/webp"),
        Arguments.of(DocumentTestFiles.fakePdf(), "application/pdf"));
  }

  @ParameterizedTest
  @MethodSource("knownTypes")
  void sniffsEachAcceptedTypeFromItsBytes(final byte[] bytes, final String type) {
    assertThat(DocumentFileType.sniff(bytes)).isEqualTo(type);
  }

  @Test
  void pdfNamedAsJpegIsStillPdf() {
    final MultipartFile renamed = new MockMultipartFile("files", "passport.jpg", "image/jpeg",
        DocumentTestFiles.fakePdf());

    assertThat(DocumentUploads.fromMultipart(List.of(renamed)))
        .singleElement()
        .satisfies(upload -> assertThat(upload.mimeType()).isEqualTo("application/pdf"));
  }

  @Test
  void htmlIsRefusedWhateverItClaimsToBe() {
    final MultipartFile html = new MockMultipartFile("files", "scan.pdf", "application/pdf",
        DocumentTestFiles.html());

    assertThat(DocumentFileType.sniff(DocumentTestFiles.html())).isNull();
    assertThatThrownBy(() -> DocumentUploads.fromMultipart(List.of(html)))
        .isInstanceOfSatisfying(ResponseStatusException.class, refused ->
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void riffThatIsNotWebpIsRefused() {
    final byte[] wav = DocumentTestFiles.webp();
    wav[8] = 'W';
    wav[9] = 'A';
    wav[10] = 'V';
    wav[11] = 'E';
    assertThat(DocumentFileType.sniff(wav)).isNull();
    assertThat(DocumentFileType.sniff(new byte[] {(byte) 0xFF})).isNull();
    assertThat(DocumentFileType.sniff(null)).isNull();
  }

  @Test
  void versionHoldsOneToTwelveFiles() {
    assertThatThrownBy(() -> DocumentUploads.check(List.of()))
        .hasMessageContaining("at least one file");
    assertThat(DocumentUploads.check(Collections.nCopies(12, DocumentTestFiles.jpeg())))
        .hasSize(12);
    assertThatThrownBy(() -> DocumentUploads.check(
        Collections.nCopies(13, DocumentTestFiles.jpeg())))
        .hasMessageContaining("up to 12 files");
  }

  @Test
  void eachFileIsAtMostTwentyMegabytesAndRequestSixty() {
    final byte[] big = new byte[(int) DocumentUploads.MAX_FILE_BYTES + 1];
    big[0] = '%';
    big[1] = 'P';
    big[2] = 'D';
    big[3] = 'F';
    big[4] = '-';
    assertThatThrownBy(() -> DocumentUploads.check(List.of(big)))
        .hasMessageContaining("20 MB");

    final byte[] full = java.util.Arrays.copyOf(big, (int) DocumentUploads.MAX_FILE_BYTES);
    assertThat(DocumentUploads.check(List.of(full, full, full))).hasSize(3);
    final byte[] one = java.util.Arrays.copyOf(big, 1024);
    assertThatThrownBy(() -> DocumentUploads.check(List.of(full, full, full, one)))
        .hasMessageContaining("60 MB");
  }

  @Test
  void anEmptyFileIsRefused() {
    assertThatThrownBy(() -> DocumentUploads.check(List.of(new byte[0])))
        .hasMessageContaining("empty");
  }

  @Test
  void anUploadNeverPrintsItsBytesAndComparesByContent() {
    final DocumentUploads.Upload first = new DocumentUploads.Upload("image/jpeg",
        DocumentTestFiles.jpeg());
    final DocumentUploads.Upload second = new DocumentUploads.Upload("image/jpeg",
        DocumentTestFiles.jpeg());

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    assertThat(first.toString()).isEqualTo("Upload[mimeType=image/jpeg, bytes=<64 bytes>]")
        .doesNotContain("17");
  }

  @Test
  void downloadsAreNamedForTheirType() {
    assertThat(DocumentFileType.extension("image/jpeg")).isEqualTo("jpg");
    assertThat(DocumentFileType.extension("image/png")).isEqualTo("png");
    assertThat(DocumentFileType.extension("image/webp")).isEqualTo("webp");
    assertThat(DocumentFileType.extension("application/pdf")).isEqualTo("pdf");
  }
}
