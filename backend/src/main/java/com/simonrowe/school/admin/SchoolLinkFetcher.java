package com.simonrowe.school.admin;

import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.ingest.DocumentDateReader;
import com.simonrowe.school.ingest.SchoolDocumentWriter;
import com.simonrowe.school.ingest.SchoolEventWriter;
import com.simonrowe.school.ingest.SchoolIngestService;
import com.simonrowe.school.ingest.SchoolLinkFilter;
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
 * Fetches a discovered link and ingests what comes back.
 *
 * <p>Almost always because a person asked: an email can link anywhere, and the ingester must not
 * be the thing that decides to make a request to an arbitrary address. The one exception is
 * {@code GmailIngestService.autoFetch}, which follows a school newsletter on the school's own
 * host during ingest — see {@link SchoolLinkFilter#isAutoFetchable} for why that is not the
 * general case, and {@link #tierFor} for why it lands in a different tier.
 *
 * <p>Reuses {@link UrlFetcher#isFetchableUrl} for the SSRF guard rather than re-deriving it. The
 * caller here is an authenticated administrator, but the URL still came out of an email — a link
 * to {@code http://169.254.169.254/} or a loopback address is exactly the shape of thing a
 * hostile sender would include, and "an admin clicked it" is not a reason to allow it.
 *
 * <p><b>Reusing the guard means reusing the redirect handling too, not only the host check.</b>
 * This class originally validated the first URL and then let the JDK client follow redirects
 * automatically, which protected exactly one hop: a link that passed the check could 302 to the
 * metadata address and be followed there transparently. The client is now
 * {@link HttpClient.Redirect#NEVER} and every hop is resolved and re-validated before it is
 * requested, the same shape as {@link UrlFetcher#fetch}.
 */
@Component
public class SchoolLinkFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolLinkFetcher.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_BYTES = 20 * 1024 * 1024;
  private static final int MAX_REDIRECTS = 5;

  private final SchoolLinkRepository links;
  private final SchoolDocumentRepository documents;
  private final SchoolDocumentWriter documentWriter;
  private final SchoolPdfExtractor pdfExtractor;
  private final SchoolAttachmentStore attachmentStore;
  private final SchoolIngestService ingestService;
  private final SchoolEventExtractor eventExtractor;
  private final SchoolEventWriter eventWriter;
  private final DocumentDateReader dateReader;
  private final SchoolLinkFilter linkFilter;
  // NEVER, not NORMAL. The JDK client follows a 3xx without re-checking the destination, so
  // with automatic redirects the SSRF guard below protects only the FIRST hop: a link that
  // passes isFetchableUrl can 302 straight to http://169.254.169.254/ and the client goes
  // there transparently. These URLs come out of email a hostile sender controls, which is
  // precisely the attack this class's javadoc describes. Hops are followed by hand instead,
  // each one re-validated before it is requested, exactly as UrlFetcher.fetch does.
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(TIMEOUT)
      .followRedirects(HttpClient.Redirect.NEVER)
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
      final DocumentDateReader dateReader,
      final SchoolLinkFilter linkFilter) {
    this.links = links;
    this.documents = documents;
    this.documentWriter = documentWriter;
    this.pdfExtractor = pdfExtractor;
    this.attachmentStore = attachmentStore;
    this.ingestService = ingestService;
    this.eventExtractor = eventExtractor;
    this.eventWriter = eventWriter;
    this.dateReader = dateReader;
    this.linkFilter = linkFilter;
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
   * The tier something fetched from a link belongs in.
   *
   * <p>Decided by <b>what the content is</b>, never by who asked for it or which door it came
   * through. Two cases:
   *
   * <p>The school's own published newsletter, on the school's own host, is public. It is public
   * because the school published it on the open internet, which is the identical reason
   * {@code SchoolIngestService.ingestWebsite} stores every other page on that host at
   * {@link Visibility#PUBLIC}. This page is absent from {@code /googlesitemap.asp} — so the crawl
   * never reaches it and only an email link does — but that is an accident of the CMS, not a
   * statement about privacy.
   *
   * <p>Inheriting the parent email's tier here, which is what this did, contradicted the
   * reasoning that lets ingest follow the link at all:
   * {@link SchoolLinkFilter#isAutoFetchable} permits it precisely because "the website crawl
   * already reads that host wholesale, so fetching one discloses nothing". The consequence was
   * not theoretical. The school moved its weekly newsletter out of the mail body and onto the
   * parent portal on 11 September 2026; the covering email carries a sentence and a link. The
   * page was fetched correctly and then filed {@code RESTRICTED} behind the approval queue, so
   * Term Time answered questions about "last week's newsletter" from a copy dated 10 July and
   * said so — and, asked for a figure from a newsletter it could not read, produced one.
   *
   * <p>Everything else — any other host, any other path — still inherits the tier of the document
   * the link was found in, which for email is {@code RESTRICTED} by construction. Fetching
   * something is not the same as publishing it.
   *
   * <p>{@code isAutoFetchable} is reused rather than a second predicate written here, so there is
   * one statement of "this is the school's own published newsletter" rather than two that drift.
   * It applies equally to the admin Fetch button: the content decides, not the requester, and an
   * administrator clicking fetch on the same URL is looking at the same public page.
   *
   * <p>Package-private so it can be asserted directly. The alternative is going through
   * {@link #fetch}, which makes a real request to a real school website — so the tier rule, the
   * thing that actually decides whether a parent can read the newsletter, would be covered only
   * by a test nobody can run offline.
   *
   * @param url the address that was fetched
   * @param parent the document the link was found in, or null when it has gone
   * @return the tier to store the fetched document at
   */
  Visibility tierFor(final String url, final SchoolDocument parent) {
    if (linkFilter.isAutoFetchable(url)) {
      return Visibility.PUBLIC;
    }
    return parent == null ? Visibility.RESTRICTED : parent.visibility();
  }

  private HttpResponse<byte[]> send(final URI uri) throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(uri)
            .header("User-Agent", "SimonRoweBot/1.0 (+https://simonrowe.dev)")
            .timeout(TIMEOUT)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static boolean isRedirect(final int statusCode) {
    return statusCode >= 300 && statusCode < 400;
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
   * <p>The new document's tier is decided by {@link #tierFor}: the school's own published
   * newsletter is public, and everything else inherits the tier of the document the link was
   * found in, which for email is {@link Visibility#RESTRICTED} by construction. Fetching
   * something is not the same as publishing it, and conflating the two in the general case would
   * let a single click put an arbitrary fetched document on the public site.
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
      final Visibility tier = tierFor(link.url(), parent);

      final byte[] body;
      final String contentType;
      try {
        URI current = URI.create(link.url().trim());
        HttpResponse<byte[]> response = send(current);
        int hops = 0;
        while (isRedirect(response.statusCode()) && hops < MAX_REDIRECTS) {
          final String location = response.headers().firstValue("location").orElse("");
          if (location.isBlank()) {
            break;
          }
          // Validated BEFORE the request, never after: reaching an internal address even once
          // is the whole harm, so a check on the response would already be too late.
          current = current.resolve(location.trim());
          if (!UrlFetcher.isFetchableUrl(current.toString())) {
            return fail(link, "It redirected somewhere that is not safe to fetch");
          }
          response = send(current);
          hops++;
        }
        if (isRedirect(response.statusCode())) {
          return fail(link, "It redirected too many times");
        }
        if (response.statusCode() != 200) {
          return fail(link, "The server returned HTTP " + response.statusCode());
        }
        body = response.body();
        contentType = response.headers().firstValue("content-type").orElse("");
      } catch (IOException e) {
        return fail(link, "Could not reach it: " + e.getMessage());
      } catch (IllegalArgumentException e) {
        return fail(link, "That address could not be read");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return fail(link, "Interrupted");
      }

      if (body.length == 0 || body.length > MAX_BYTES) {
        return fail(link, "Nothing usable came back (" + body.length + " bytes)");
      }

      final Extracted extracted = extractText(body, contentType);
      if (extracted == null || extracted.text() == null || extracted.text().isBlank()) {
        // An image is a legitimate thing to link to and there is nothing to index in one, so
        // this is a decline rather than an error.
        return fail(link, "No readable text — it may be an image or an unsupported format");
      }

      final String text = extracted.text();
      final SchoolSourceType type = sourceTypeFor(body, link.url());
      final SchoolDocumentWriter.WriteResult result = documentWriter.write(
          type,
          link.url(),
          titleFor(link, extracted.title()),
          text,
          publishedAtFor(type, link, text),
          // A fetched page inherits its parent's year-group scope for the same reason it
          // inherits the tier: it is being read because of the document that named it. A note
          // about Year 6 open evenings leads only to Year 6 pages.
          parent == null ? List.of() : parent.yearGroups(),
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

  /**
   * The source type a fetched address belongs under.
   *
   * <p>A PDF is a PDF wherever it came from. Everything else splits on the host, because
   * {@code SchoolQueryService.communicationsBetween} — "what did the school send or publish" —
   * reads an allowlist of source types that includes {@link SchoolSourceType#WEBSITE_PAGE}. A
   * secondary school's admissions page filed under that type is reported to a parent as
   * something Kilmorie published. See {@link SchoolSourceType#EXTERNAL_PAGE}.
   *
   * <p>Package-private for the same reason as {@link #tierFor}: reaching it through
   * {@link #fetch} means making a real request to a real third-party website, so the rule that
   * decides whether another school's page is reported as this school's would be covered only by
   * a test nobody can run offline.
   *
   * @param body the bytes fetched, to recognise a PDF by its magic number
   * @param url the address fetched
   * @return the type to store it under
   */
  SchoolSourceType sourceTypeFor(final byte[] body, final String url) {
    if (isPdf(body)) {
      return SchoolSourceType.PDF;
    }
    return linkFilter.isOnSchoolHost(url)
        ? SchoolSourceType.WEBSITE_PAGE
        : SchoolSourceType.EXTERNAL_PAGE;
  }

  /**
   * A title for the fetched document.
   *
   * <p>The link's anchor text when it is real text, the page's own {@code <title>} otherwise.
   * Links pasted into the admin console are bare addresses — WhatsApp sends no anchor text —
   * so without this the document is titled {@code https://www.harrisdulwichboys.org.uk/…}, and
   * so is every citation an answer builds from it. It also matters to extraction: the title is
   * the only place the school's <i>name</i> appears for a page headed only "Open Events".
   *
   * @param link the link being fetched
   * @param pageTitle the page's own title, or null
   * @return a title, never blank
   */
  static String titleFor(final SchoolLink link, final String pageTitle) {
    final String anchor = link.anchorText() == null ? "" : link.anchorText().trim();
    final boolean anchorIsJustTheUrl = anchor.isEmpty() || anchor.equalsIgnoreCase(link.url());
    if (anchorIsJustTheUrl && pageTitle != null && !pageTitle.isBlank()) {
      return pageTitle.trim();
    }
    return anchorIsJustTheUrl ? link.url() : anchor;
  }

  private Extracted extractText(final byte[] body, final String contentType) {
    if (isPdf(body)) {
      return new Extracted(pdfExtractor.extractTextFromBytes(body), null);
    }
    if (contentType.toLowerCase(java.util.Locale.ROOT).contains("html")) {
      final org.jsoup.nodes.Document parsed =
          Jsoup.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8));
      // Read before the strip: title() lives in <head>, which survives, but reading it first
      // keeps the two independent of which elements the next edit decides to remove.
      final String title = parsed.title();
      parsed.select("script, style, nav, header, footer").remove();
      return new Extracted(parsed.body() == null ? null : parsed.body().text(), title);
    }
    return null;
  }

  /**
   * What came out of a fetched response.
   *
   * @param text the readable text, or null when there is none
   * @param title the page's own title, or null for a PDF or an untitled page
   */
  private record Extracted(String text, String title) {
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
