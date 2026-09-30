package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two things the crawl now reads about a PDF besides its text: what the page calls it, and
 * when the school's server says it was uploaded.
 */
class SchoolPdfExtractorLinksAndDatesTest {

  private final SchoolPdfExtractor extractor = new SchoolPdfExtractor();
  private HttpServer server;
  private final AtomicReference<String> rangeHeader = new AtomicReference<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/dated.pdf", exchange -> {
      rangeHeader.set(exchange.getRequestHeaders().getFirst("Range"));
      exchange.getResponseHeaders().add("Last-Modified", "Thu, 15 Jan 2026 10:39:47 GMT");
      exchange.getResponseHeaders().add("Content-Range", "bytes 0-0/364358");
      exchange.sendResponseHeaders(206, 1);
      exchange.getResponseBody().write('%');
      exchange.close();
    });
    server.createContext("/undated.pdf", exchange -> {
      exchange.sendResponseHeaders(200, 1);
      exchange.getResponseBody().write('%');
      exchange.close();
    });
    server.createContext("/garbled.pdf", exchange -> {
      exchange.getResponseHeaders().add("Last-Modified", "last Tuesday");
      exchange.sendResponseHeaders(200, 1);
      exchange.getResponseBody().write('%');
      exchange.close();
    });
    server.createContext("/gone.pdf", exchange -> {
      exchange.getResponseHeaders().add("Last-Modified", "Thu, 15 Jan 2026 10:39:47 GMT");
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private String url(final String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  @Test
  @DisplayName("the server's upload date is read with a one-byte ranged request")
  void lastModifiedIsRead() {
    assertThat(extractor.lastModified(url("/dated.pdf")))
        .contains(Instant.parse("2026-01-15T10:39:47Z"));
    // Not HEAD: the school's server never answers one.
    assertThat(rangeHeader.get()).isEqualTo("bytes=0-0");
  }

  @Test
  @DisplayName("no date, a date that cannot be read, or an error response gives no date")
  void missingOrBadDatesGiveNothing() {
    assertThat(extractor.lastModified(url("/undated.pdf"))).isEmpty();
    assertThat(extractor.lastModified(url("/garbled.pdf"))).isEmpty();
    assertThat(extractor.lastModified(url("/gone.pdf"))).isEmpty();
    assertThat(extractor.lastModified("http://127.0.0.1:1/nothing-listens.pdf")).isEmpty();
  }

  @Test
  @DisplayName("each PDF link keeps the words the page uses for it")
  void linksKeepTheirText() {
    final String html = """
        <a href="/files/CCD3F6CE6CD4833E540D3E4545A4D6EA.pdf">Spellings list&nbsp;for Spring 1</a>
        <a href="/files/CCD3F6CE6CD4833E540D3E4545A4D6EA.pdf">again</a>
        <a href="/files/D88A.pdf"><img src="icon.png"></a>
        <a href="/files/D88A.pdf">Fun ways to learn spellings at home</a>
        <a href="/attachments/download.asp?file=739&type=pdf">Year 1 letter</a>
        <a href="/not-a-pdf">Elsewhere</a>
        """;

    final List<SchoolPdfExtractor.PdfLink> links =
        extractor.findPdfLinksWithText(html, "https://www.kilmorieschool.co.uk/year-four");

    assertThat(links).containsExactly(
        new SchoolPdfExtractor.PdfLink(
            "https://www.kilmorieschool.co.uk/files/CCD3F6CE6CD4833E540D3E4545A4D6EA.pdf",
            "Spellings list for Spring 1"),
        new SchoolPdfExtractor.PdfLink("https://www.kilmorieschool.co.uk/files/D88A.pdf",
            "Fun ways to learn spellings at home"),
        new SchoolPdfExtractor.PdfLink(
            "https://www.kilmorieschool.co.uk/attachments/download.asp?file=739&type=pdf",
            "Year 1 letter"));
  }
}
