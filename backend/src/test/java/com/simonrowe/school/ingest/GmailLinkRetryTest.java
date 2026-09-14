package com.simonrowe.school.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.admin.SchoolLinkFetcher;
import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.classify.TierClassifier;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.SchoolSyncStateRepository;
import com.simonrowe.school.model.Visibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A newsletter whose auto-fetch failed once must not stay lost for ever.
 *
 * <p>Ingest used to skip every link row it had already seen before it got as far as the
 * auto-fetch. The email's own text never changes, so a newsletter that hit one timeout or one
 * 502 was found and skipped again on every subsequent sync: the {@code FAILED} row sat there
 * permanently and nothing but a human clicking Fetch in the admin console could recover it. The
 * symptom is a week's newsletter simply absent, which from the outside is indistinguishable
 * from the school not having sent one that week.
 *
 * <p>The other three statuses are decisions, and the retry must not reach across them — {@code
 * PENDING} in particular, which is a link waiting for the human judgement this whole mechanism
 * exists to ask for.
 */
class GmailLinkRetryTest {

  private static final String MESSAGE_ID = "msg-newsletter";
  private static final String NEWSLETTER =
      "https://www.kilmorieschool.co.uk/parentportal/newsletter/?id=163";
  private static final String EMAIL_DOC_ID =
      SchoolIds.documentId(SchoolSourceType.EMAIL, MESSAGE_ID);
  private static final String LINK_ID =
      SchoolIds.documentId(SchoolSourceType.EMAIL, EMAIL_DOC_ID + '|' + NEWSLETTER);

  private SchoolLinkRepository links;
  private SchoolLinkFetcher linkFetcher;
  private GmailIngestService service;

  @BeforeEach
  void setUp() throws Exception {
    final GmailClient gmail = mock(GmailClient.class);
    final SchoolDocumentWriter documentWriter = mock(SchoolDocumentWriter.class);
    final SchoolLinkFilter linkFilter = mock(SchoolLinkFilter.class);
    final SchoolEventExtractor eventExtractor = mock(SchoolEventExtractor.class);
    links = mock(SchoolLinkRepository.class);
    linkFetcher = mock(SchoolLinkFetcher.class);

    when(gmail.isConfigured()).thenReturn(true);
    when(gmail.listMessageIds(anyString())).thenReturn(List.of(MESSAGE_ID));
    when(gmail.fetchMessage(MESSAGE_ID)).thenReturn(Optional.of(message()));
    when(eventExtractor.extract(any())).thenReturn(List.of());
    when(linkFilter.isWorthOffering(NEWSLETTER)).thenReturn(true);
    when(linkFilter.isAutoFetchable(NEWSLETTER)).thenReturn(true);
    // Unchanged, which is the ordinary state of an email that arrived last week — and precisely
    // the state in which a failed link used to be unreachable for ever.
    when(documentWriter.write(eq(SchoolSourceType.EMAIL), anyString(), anyString(), anyString(),
        any(), any(), any()))
        .thenReturn(new SchoolDocumentWriter.WriteResult(email(), false));

    service = new GmailIngestService(
        properties(), gmail, mock(TierClassifier.class), documentWriter,
        mock(SchoolSyncStateRepository.class), mock(SchoolIngestService.class),
        mock(SchoolPdfExtractor.class), eventExtractor, mock(SchoolEventWriter.class),
        mock(SchoolAttachmentStore.class), links, linkFilter, linkFetcher);
  }

  @Test
  @DisplayName("a failed auto-fetch is attempted again on the next sync")
  void retriesFailedFetch() {
    when(links.findById(LINK_ID)).thenReturn(Optional.of(link(SchoolLink.Status.FAILED)));

    service.sync();

    verify(linkFetcher).fetch(LINK_ID);
    // The row already exists; re-recording it would reset the failure reason an operator may be
    // reading, and there is nothing new to record.
    verify(links, never()).save(any());
  }

  @Test
  @DisplayName("a link a human has not yet ruled on is left alone")
  void neverOverridesPendingDecision() {
    // PENDING is the approval queue. Re-fetching it nightly would be ingest quietly making the
    // decision the queue exists to ask a person for.
    when(links.findById(LINK_ID)).thenReturn(Optional.of(link(SchoolLink.Status.PENDING)));

    service.sync();

    verify(linkFetcher, never()).fetch(anyString());
  }

  @Test
  @DisplayName("a link already fetched or declined is never fetched again")
  void neverRepeatsSettledLink() {
    when(links.findById(LINK_ID)).thenReturn(Optional.of(link(SchoolLink.Status.FETCHED)));
    service.sync();

    when(links.findById(LINK_ID)).thenReturn(Optional.of(link(SchoolLink.Status.IGNORED)));
    service.sync();

    verify(linkFetcher, never()).fetch(anyString());
  }

  @Test
  @DisplayName("a link seen for the first time is recorded and then fetched")
  void recordsThenFetchesNewLink() {
    when(links.findById(LINK_ID)).thenReturn(Optional.empty());

    service.sync();

    // In that order: the row is written first, so a fetch that throws leaves the link recorded
    // rather than losing it altogether.
    verify(links).save(any(SchoolLink.class));
    verify(linkFetcher).fetch(LINK_ID);
  }

  private SchoolLink link(final SchoolLink.Status status) {
    return new SchoolLink(LINK_ID, EMAIL_DOC_ID, NEWSLETTER, "Newsletter - 11th September 2026",
        Instant.parse("2026-09-11T15:22:00Z"), status, null,
        status == SchoolLink.Status.FAILED ? "Could not reach it: timed out" : null);
  }

  private GmailMessage message() {
    return new GmailMessage(
        MESSAGE_ID, "Weekly Newsletter", "info@kilmorie.lewisham.sch.uk",
        "Kilmorie Primary School", Instant.parse("2026-09-11T15:03:59Z"),
        "Please find this week's newsletter below for the latest school news:",
        List.of(),
        List.of(new GmailMessage.Link(NEWSLETTER, "Newsletter - 11th September 2026")));
  }

  private SchoolDocument email() {
    return new SchoolDocument(
        EMAIL_DOC_ID, SchoolSourceType.EMAIL, MESSAGE_ID, "Weekly Newsletter",
        "Please find this week's newsletter below for the latest school news:",
        Instant.parse("2026-09-11T15:03:59Z"), Instant.parse("2026-09-11T15:22:00Z"),
        Visibility.RESTRICTED, null, null, null, null, false, List.of(), "hash", null);
  }

  private SchoolProperties properties() {
    return new SchoolProperties(true, LocalDate.of(2026, 7, 1),
        List.of("kilmorie.lewisham.sch.uk"), List.of(), null,
        "https://www.kilmorieschool.co.uk", null, 0, null, null, 0, null, null);
  }
}
