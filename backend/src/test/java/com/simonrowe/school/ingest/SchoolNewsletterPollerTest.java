package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.admin.SchoolLinkFetcher;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SchoolNewsletterPollerTest {

  private static final String BASE = "https://www.kilmorieschool.co.uk/parentportal/newsletter/";

  private final SchoolWebsiteCrawler crawler = mock(SchoolWebsiteCrawler.class);
  private final SchoolDocumentRepository documents = mock(SchoolDocumentRepository.class);
  private final SchoolLinkRepository links = mock(SchoolLinkRepository.class);
  private final SchoolLinkFetcher fetcher = mock(SchoolLinkFetcher.class);

  @BeforeEach
  void setUp() {
    when(documents.findBySourceTypeAndSourceRef(any(), anyString())).thenReturn(Optional.empty());
    when(links.findById(anyString())).thenReturn(Optional.empty());
    when(fetcher.fetch(anyString())).thenAnswer(call -> Optional.of(new SchoolLink(
        call.getArgument(0), null, "u", "a", Instant.now(), SchoolLink.Status.FETCHED,
        "doc", null)));
  }

  private static SchoolWebsiteCrawler.PortalNewsletter issue(final int id, final LocalDate date) {
    return new SchoolWebsiteCrawler.PortalNewsletter(
        String.valueOf(id), BASE + "?id=" + id, "Newsletter " + id, date);
  }

  private SchoolNewsletterPoller poller(final LocalDate cutoff) {
    final SchoolProperties properties = new SchoolProperties(
        true, cutoff, null, null, null, null, null, 0, null, null, null, 0, null, null);
    return new SchoolNewsletterPoller(crawler, documents, links, fetcher, properties);
  }

  @Test
  @DisplayName("fetches an issue that no email linked to, through a link row with no source")
  void fetchesTheMissingIssue() {
    when(crawler.listNewsletters()).thenReturn(List.of(issue(165, LocalDate.of(2026, 9, 25))));

    assertThat(poller(null).poll()).isEqualTo(1);

    final ArgumentCaptor<SchoolLink> saved = ArgumentCaptor.forClass(SchoolLink.class);
    verify(links).save(saved.capture());
    assertThat(saved.getValue().sourceDocumentId()).isNull();
    assertThat(saved.getValue().url()).isEqualTo(BASE + "?id=165");
    assertThat(saved.getValue().status()).isEqualTo(SchoolLink.Status.PENDING);
    verify(fetcher).fetch(saved.getValue().id());
  }

  @Test
  @DisplayName("leaves an issue already held alone, whichever path brought it in")
  void skipsWhatIsHeld() {
    when(crawler.listNewsletters()).thenReturn(List.of(issue(171, LocalDate.of(2026, 10, 23))));
    when(documents.findBySourceTypeAndSourceRef(SchoolSourceType.WEBSITE_PAGE, BASE + "?id=171"))
        .thenReturn(Optional.of(mock(SchoolDocument.class)));

    assertThat(poller(null).poll()).isZero();

    verify(fetcher, never()).fetch(anyString());
  }

  @Test
  @DisplayName("skips anything published before the ingest cutoff")
  void respectsTheCutoff() {
    when(crawler.listNewsletters()).thenReturn(List.of(
        issue(171, LocalDate.of(2026, 10, 23)), issue(162, LocalDate.of(2026, 7, 17))));

    assertThat(poller(LocalDate.of(2026, 9, 1)).poll()).isEqualTo(1);

    verify(documents, never()).findBySourceTypeAndSourceRef(any(), eq(BASE + "?id=162"));
  }

  @Test
  @DisplayName("never fetches an issue an administrator ignored")
  void respectsAnIgnore() {
    when(crawler.listNewsletters()).thenReturn(List.of(issue(165, LocalDate.of(2026, 9, 25))));
    when(links.findById(anyString())).thenReturn(Optional.of(new SchoolLink("id", null,
        BASE + "?id=165", "x", Instant.now(), SchoolLink.Status.IGNORED, null, null)));

    assertThat(poller(null).poll()).isZero();

    verify(fetcher, never()).fetch(anyString());
  }

  @Test
  @DisplayName("tries a failed fetch again on the next run without writing a second row")
  void retriesFailedFetch() {
    when(crawler.listNewsletters()).thenReturn(List.of(issue(165, LocalDate.of(2026, 9, 25))));
    when(links.findById(anyString())).thenReturn(Optional.of(new SchoolLink("id", null,
        BASE + "?id=165", "x", Instant.now(), SchoolLink.Status.FAILED, null, "HTTP 502")));

    assertThat(poller(null).poll()).isEqualTo(1);

    verify(links, never()).save(any());
    verify(fetcher).fetch(anyString());
  }

  @Test
  @DisplayName("looks at only the newest issues, so the years-long list is never walked")
  void capsTheRun() {
    final List<SchoolWebsiteCrawler.PortalNewsletter> many = new ArrayList<>();
    IntStream.range(0, 30).forEach(n -> many.add(issue(200 - n, LocalDate.of(2026, 10, 1))));
    when(crawler.listNewsletters()).thenReturn(many);

    assertThat(poller(null).poll()).isEqualTo(SchoolNewsletterPoller.MAX_PER_RUN);
  }

  @Test
  @DisplayName("a failed fetch does not stop the rest of the run")
  void carriesOnAfterFailure() {
    when(crawler.listNewsletters()).thenReturn(List.of(
        issue(171, LocalDate.of(2026, 10, 23)), issue(165, LocalDate.of(2026, 9, 25))));
    when(fetcher.fetch(anyString()))
        .thenThrow(new IllegalStateException("boom"))
        .thenAnswer(call -> Optional.of(new SchoolLink(call.getArgument(0), null, "u", "a",
            Instant.now(), SchoolLink.Status.FETCHED, "doc", null)));

    assertThat(poller(null).poll()).isEqualTo(1);
  }

  @Test
  @DisplayName("fetches nothing when the list cannot be read")
  void nothingWhenTheListIsEmpty() {
    when(crawler.listNewsletters()).thenReturn(List.of());

    assertThat(poller(null).poll()).isZero();

    verify(fetcher, never()).fetch(anyString());
  }
}
