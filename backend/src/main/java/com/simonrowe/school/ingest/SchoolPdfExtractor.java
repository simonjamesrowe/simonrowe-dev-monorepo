package com.simonrowe.school.ingest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pulls text out of the school's PDFs.
 *
 * <p>The term dates, the enrichment timetable and the lunch menu are PDF-only — the enrichment
 * timetable is in fact the single most current document on the site — so without this the
 * assistant cannot answer "when do clubs start" from anything but the calendar.
 *
 * <p>Two URL conventions have to be handled, because the CMS has accumulated both:
 * {@code /attachments/download.asp?file=N} and
 * {@code /_site/data/files/users/.../<MD5>.pdf}. The numeric ids are sequential rather than
 * date-derived, so they cannot be constructed — a listing page has to be scraped for the links.
 */
@Component
public class SchoolPdfExtractor {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolPdfExtractor.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_BYTES = 20 * 1024 * 1024;

  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(TIMEOUT)
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  /**
   * Finds PDF links on a page.
   *
   * @param html the page HTML
   * @param baseUri the page URL, for resolving relative links
   * @return absolute PDF URLs, in document order and de-duplicated
   */
  public List<String> findPdfLinks(final String html, final String baseUri) {
    if (html == null || html.isBlank()) {
      return List.of();
    }
    final Set<String> links = new LinkedHashSet<>();
    for (Element anchor : Jsoup.parse(html, baseUri).select("a[href]")) {
      final String href = anchor.absUrl("href");
      if (href.isEmpty()) {
        continue;
      }
      final String lower = href.toLowerCase(java.util.Locale.ROOT);
      if (lower.endsWith(".pdf") || lower.contains("download.asp?file=")) {
        links.add(href);
      }
    }
    return List.copyOf(links);
  }

  /**
   * Downloads a PDF and extracts its text.
   *
   * <p>Returns null rather than throwing on any failure. A school website with a broken
   * attachment link is completely ordinary, and an ingest pass that aborted on the first one
   * would leave the rest of the crawl undone.
   *
   * @param url the PDF URL
   * @return the extracted text, or null if it could not be read
   */
  public String extractText(final String url) {
    try {
      final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .header("User-Agent", "SimonRoweBot/1.0 (+https://simonrowe.dev)")
          .timeout(TIMEOUT)
          .GET()
          .build();
      final HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        LOG.debug("PDF {} returned {}", url, response.statusCode());
        return null;
      }
      final byte[] body = response.body();
      if (body.length == 0 || body.length > MAX_BYTES) {
        LOG.debug("PDF {} is {} bytes, skipping", url, body.length);
        return null;
      }
      // Not every download.asp link is actually a PDF - some are Word documents or images.
      // Checking the magic bytes is cheaper and more reliable than trusting the URL or the
      // Content-Type this CMS reports.
      if (!(body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F')) {
        LOG.debug("{} is not a PDF", url);
        return null;
      }
      return textOf(body);
    } catch (IOException | IllegalArgumentException e) {
      LOG.debug("Could not read PDF {}: {}", url, e.getMessage());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /**
   * Extracts text from PDF bytes already in hand — an email attachment, typically.
   *
   * <p>Checks the magic bytes rather than trusting a declared MIME type or a filename: senders
   * mislabel attachments routinely, and PDFBox on a Word document produces a confusing failure
   * rather than a clear one.
   *
   * @param bytes the file contents
   * @return the extracted text, or null when it is not a readable PDF
   */
  public String extractTextFromBytes(final byte[] bytes) {
    if (bytes == null || bytes.length < 5 || bytes.length > MAX_BYTES) {
      return null;
    }
    if (!(bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F')) {
      return null;
    }
    return textOf(bytes);
  }

  private String textOf(final byte[] bytes) {
    try (PDDocument document = Loader.loadPDF(bytes)) {
      final PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      final String text = stripper.getText(document);
      return text == null || text.isBlank() ? null : normalise(text);
    } catch (IOException | RuntimeException e) {
      LOG.debug("Could not extract PDF text: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Collapses the whitespace PDF extraction produces.
   *
   * <p>A timetable laid out as a table comes out with long runs of spaces standing in for
   * columns. Left alone those dominate the token count and push the useful text out of a chunk.
   *
   * @param text raw extracted text
   * @return text with runs of whitespace collapsed and blank lines removed
   */
  private String normalise(final String text) {
    final List<String> lines = new ArrayList<>();
    for (String line : text.split("\\R")) {
      final String collapsed = line.replaceAll("\\s{2,}", " ").trim();
      if (!collapsed.isEmpty()) {
        lines.add(collapsed);
      }
    }
    return String.join("\n", lines);
  }
}
