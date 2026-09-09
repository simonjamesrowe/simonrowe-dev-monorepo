package com.simonrowe.school.admin;

import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.ingest.DocumentDateReader;
import com.simonrowe.school.ingest.SchoolDocumentWriter;
import com.simonrowe.school.ingest.SchoolEventWriter;
import com.simonrowe.school.ingest.SchoolIngestService;
import com.simonrowe.school.ingest.SchoolPdfExtractor;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.webfetch.UrlFetcher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fetches a discovered link, but only when a person has asked for it.
 *
 * <p>Nothing calls this from the ingest path. It exists behind one admin action, which is the
 * whole point: an email can link anywhere, and the ingester must never be the thing that decides
 * to make a request to an arbitrary address.
 *
 * <p>Reuses {@link UrlFetcher#isFetchableUrl} for the SSRF guard rather than re-deriving it. The
 * caller here is an authenticated administrator, but the URL still came out of an email — a link
 * to {@code http://169.254.169.254/} or a loopback address is exactly the shape of thing a
 * hostile sender would include, and "an admin clicked it" is not a reason to allow it.
 */
@Component
public class SchoolLinkFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolLinkFetcher.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_BYTES = 20 * 1024 * 1024;

  private final SchoolLinkRepository links;
  private final SchoolDocumentRepository documents;
  private final SchoolDocumentWriter documentWriter;
  private final SchoolPdfExtractor pdfExtractor;
  private final SchoolAttachmentStore attachmentStore;
  private final SchoolIngestService ingestService;
  private final SchoolEventExtractor eventExtractor;
  private final SchoolEventWriter eventWriter;
  private final DocumentDateReader dateReader;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(TIMEOUT)
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @SuppressWarnings("checkstyle:ParameterNumber")
  public SchoolLinkFetcher(
      final SchoolLinkRepository links,
      final SchoolDocumentRepository documents,
      final SchoolDocumentWriter documentWriter,
      final SchoolPdfExtractor pdfExtractor,
      final SchoolAttachmentStore attachmentStore,
      final SchoolIngestService ingestService,
      final SchoolEventExtractor eventExtractor,
      final SchoolEventWriter eventWriter,
      final DocumentDateReader dateReader) {
    this.links = links;
    this.documents = documents;
    this.documentWriter = documentWriter;
    this.pdfExtractor = pdfExtractor;
    this.attachmentStore = attachmentStore;
    this.ingestService = ingestService;
    this.eventExtractor = eventExtractor;
    this.eventWriter = eventWriter;
    this.dateReader = dateReader;
  }

  /**
   * The best publication date for something fetched from a link.
   *
   * <p>This was {@code Instant.now()}, which is the same fault the website PDF path had: a
   * newsletter for 17 July, fetched in September, was recorded as published in September. That is
   * not only a wrong label — {@code publishedAt} is the reference date
   * {@code SchoolEventExtractor} resolves relative dates against, so every "next Friday" in a
   * fetched newsletter landed weeks late.
   *
   * <p>The anchor text is consulted after the body because this CMS puts the date in the link
   * title ("… Newsletter - 17th July 2026") while the page itself often leads with navigation.
   * Falling back to the stored date keeps a re-fetch from re-dating an undated page.
   *
   * @param type what kind of document this became
   * @param link the link being fetched, for its anchor text and address
   * @param text the extracted text
   * @return the date to record
   */
  private Instant publishedAtFor(
      final SchoolSourceType type, final SchoolLink link, final String text) {
    final LocalDate today = LocalDate.now();
    return dateReader.fromText(text, today)
        .or(() -> dateReader.fromText(link.anchorText(), today))
        .map(date -> date.atStartOfDay(ZoneId.systemDefault()).toInstant())
        .or(() -> documentWriter.existingPublishedAt(type, link.url()))
        .orElseGet(Instant::now);
  }

  /**
   * Declines a link permanently.
   *
   * @param id the link id
   * @return the updated link, or empty when unknown
   */
  public Optional<SchoolLink> ignore(final String id) {
    return links.findById(id).map(link -> links.save(new SchoolLink(
        link.id(), link.sourceDocumentId(), link.url(), link.anchorText(),
        link.discoveredAt(), SchoolLink.Status.IGNORED, null, null)));
  }

  /**
   * Fetches a link and ingests what comes back.
   *
   * <p>The new document inherits the tier of the email the link was found in, which is
   * {@link Visibility#RESTRICTED} by construction. Fetching something is not the same as
   * publishing it, and conflating the two would let a single click put an arbitrary fetched
   * document on the public site.
   *
   * @param id the link id
   * @return the updated link, or empty when unknown
   */
  public Optional<SchoolLink> fetch(final String id) {
    return links.findById(id).map(link -> {
      if (!UrlFetcher.isFetchableUrl(link.url())) {
        return fail(link, "That address is not safe to fetch (non-public or unsupported scheme)");
      }
      final SchoolDocument parent = documents.findById(link.sourceDocumentId()).orElse(null);
      final Visibility tier = parent == null ? Visibility.RESTRICTED : parent.visibility();

      final byte[] body;
      final String contentType;
      try {
        final HttpResponse<byte[]> response = httpClient.send(
            HttpRequest.newBuilder(URI.create(link.url()))
                .header("User-Agent", "SimonRoweBot/1.0 (+https://simonrowe.dev)")
                .timeout(TIMEOUT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
          return fail(link, "The server returned HTTP " + response.statusCode());
        }
        body = response.body();
        contentType = response.headers().firstValue("content-type").orElse("");
      } catch (IOException e) {
        return fail(link, "Could not reach it: " + e.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return fail(link, "Interrupted");
      }

      if (body.length == 0 || body.length > MAX_BYTES) {
        return fail(link, "Nothing usable came back (" + body.length + " bytes)");
      }

      final String text = extractText(body, contentType);
      if (text == null || text.isBlank()) {
        // An image is a legitimate thing to link to and there is nothing to index in one, so
        // this is a decline rather than an error.
        return fail(link, "No readable text — it may be an image or an unsupported format");
      }

      final SchoolSourceType type =
          isPdf(body) ? SchoolSourceType.PDF : SchoolSourceType.WEBSITE_PAGE;
      final SchoolDocumentWriter.WriteResult result = documentWriter.write(
          type,
          link.url(),
          link.anchorText(),
          text,
          publishedAtFor(type, link, text),
          List.of(),
          tier);

      final SchoolDocument fetched = result.document();
      if (isPdf(body)) {
        attachmentStore.store(fetched.id(), body);
      }
      ingestService.embed(fetched);
      for (SchoolEvent event : eventExtractor.extract(fetched)) {
        eventWriter.write(event);
      }
      LOG.info("Fetched linked content from {} into document {}", link.url(), fetched.id());

      return links.save(new SchoolLink(
          link.id(), link.sourceDocumentId(), link.url(), link.anchorText(),
          link.discoveredAt(), SchoolLink.Status.FETCHED, fetched.id(), null));
    });
  }

  private String extractText(final byte[] body, final String contentType) {
    if (isPdf(body)) {
      return pdfExtractor.extractTextFromBytes(body);
    }
    if (contentType.toLowerCase(java.util.Locale.ROOT).contains("html")) {
      final org.jsoup.nodes.Document parsed =
          Jsoup.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8));
      parsed.select("script, style, nav, header, footer").remove();
      return parsed.body() == null ? null : parsed.body().text();
    }
    return null;
  }

  private static boolean isPdf(final byte[] body) {
    return body.length > 4
        && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F';
  }

  private SchoolLink fail(final SchoolLink link, final String reason) {
    LOG.warn("Fetch of {} failed: {}", link.url(), reason);
    return links.save(new SchoolLink(
        link.id(), link.sourceDocumentId(), link.url(), link.anchorText(),
        link.discoveredAt(), SchoolLink.Status.FAILED, null, reason));
  }
}
