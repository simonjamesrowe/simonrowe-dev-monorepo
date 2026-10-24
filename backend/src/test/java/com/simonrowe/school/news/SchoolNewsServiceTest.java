package com.simonrowe.school.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.ingest.SchoolLinkFilter;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchoolNewsServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");
  private static final String PORTAL =
      "https://www.kilmorieschool.co.uk/parentportal/newsletter/?id=";

  private final SchoolDocumentRepository documents = mock(SchoolDocumentRepository.class);
  private final SchoolLinkRepository links = mock(SchoolLinkRepository.class);
  private final NewsletterSummaryRepository summaries = mock(NewsletterSummaryRepository.class);
  private final SchoolNewsletterSummariser summariser = mock(SchoolNewsletterSummariser.class);
  private final SchoolNewsService service = new SchoolNewsService(documents, links, summaries,
      summariser,
      new SchoolLinkFilter(new SchoolProperties(
          true, null, null, null, null, null, null, 0, null, null, null, 0, null, null)),
      Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void setUp() {
    when(summaries.findAllById(anyList())).thenReturn(List.of());
  }

  private static SchoolDocument page(final String id, final String url, final String date,
      final String body) {
    return new SchoolDocument(id, SchoolSourceType.WEBSITE_PAGE, url, "Newsletter " + id, body,
        Instant.parse(date + "T00:00:00Z"), NOW, Visibility.PUBLIC, null, null, null, null,
        false, List.of(), "hash-" + id, null);
  }

  private static SchoolDocument email(final String id, final String subject) {
    return new SchoolDocument(id, SchoolSourceType.EMAIL, "msg-" + id, subject, "body",
        Instant.parse("2026-10-22T16:00:00Z"), NOW, Visibility.PUBLIC, null, null, null, null,
        false, List.of("Year 6"), "hash-" + id, null);
  }

  private void pages(final SchoolDocument... found) {
    when(documents.findPublishedBetween(eq(List.of(Visibility.PUBLIC)),
        eq(List.of(SchoolSourceType.WEBSITE_PAGE)), any(), any())).thenReturn(List.of(found));
  }

  @Test
  @DisplayName("lists only the school's portal newsletters, never other website pages")
  void onlyNewsletters() {
    pages(page("171", PORTAL + "171", "2026-10-23", "a"),
        page("term", "https://www.kilmorieschool.co.uk/term-dates", "2026-10-20", "b"));

    assertThat(service.newsletters(6)).extracting(SchoolNewsService.Newsletter::id)
        .containsExactly("171");
  }

  @Test
  @DisplayName("reads public documents only, whoever is asking")
  void publicOnly() {
    pages();

    service.newsletters(6);

    verify(documents).findPublishedBetween(eq(List.of(Visibility.PUBLIC)), any(), any(), any());
  }

  @Test
  @DisplayName("shows one card per Friday when an issue is published twice, keeping the fuller")
  void oneIssuePerDate() {
    pages(page("167", PORTAL + "167", "2026-10-02", "a longer re-publish"),
        page("166", PORTAL + "166", "2026-10-02", "shorter"));

    assertThat(service.newsletters(6)).extracting(SchoolNewsService.Newsletter::id)
        .containsExactly("167");
  }

  @Test
  @DisplayName("serves a summary only while it matches the newsletter's text")
  void staleSummariesAreNotServed() {
    pages(page("171", PORTAL + "171", "2026-10-23", "a"),
        page("170", PORTAL + "170", "2026-10-16", "b"));
    when(summaries.findAllById(anyList())).thenReturn(List.of(
        new NewsletterSummary("171", "hash-171", "Current", List.of(), "m", NOW),
        new NewsletterSummary("170", "an-older-text", "Stale", List.of(), "m", NOW)));

    final List<SchoolNewsService.Newsletter> found = service.newsletters(6);

    assertThat(found.get(0).summary().headline()).isEqualTo("Current");
    assertThat(found.get(1).summary()).isNull();
  }

  @Test
  @DisplayName("writes summaries only where one is missing or stale, a few per run")
  void summarisesWhatIsMissing() {
    pages(page("171", PORTAL + "171", "2026-10-23", "a"),
        page("170", PORTAL + "170", "2026-10-16", "b"),
        page("169", PORTAL + "169", "2026-10-09", "c"),
        page("167", PORTAL + "167", "2026-10-02", "d"),
        page("165", PORTAL + "165", "2026-09-25", "e"));
    when(summaries.findAllById(anyList())).thenReturn(List.of(
        new NewsletterSummary("171", "hash-171", "Done", List.of(), "m", NOW)));
    when(summariser.summarise(any())).thenAnswer(call -> {
      final SchoolDocument document = call.getArgument(0);
      return Optional.of(new NewsletterSummary(document.id(), document.contentHash(), "New",
          List.of(), "m", NOW));
    });

    assertThat(service.summariseMissing()).isEqualTo(SchoolNewsService.MAX_SUMMARIES_PER_RUN);

    verify(summariser, never()).summarise(org.mockito.ArgumentMatchers.argThat(
        document -> document.id().equals("171")));
    verify(summaries, times(SchoolNewsService.MAX_SUMMARIES_PER_RUN)).save(any());
  }

  @Test
  @DisplayName("leaves out the email that only covers a newsletter, recognised by its link")
  void dropsCoveringEmails() {
    when(documents.findPublishedBetween(eq(List.of(Visibility.PUBLIC)),
        eq(List.of(SchoolSourceType.EMAIL)), any(), any())).thenReturn(List.of(
            email("cover", "Happy Friday - 23rd October Newsletter"),
            email("safety", "October Online Safety Newsletter"),
            email("checks", "Reception and Year 6 - Height and Weight")));
    when(links.findBySourceDocumentIdIn(anyList())).thenReturn(List.of(
        new SchoolLink("l1", "cover", PORTAL + "171", "Newsletter", NOW,
            SchoolLink.Status.FETCHED, "171", null),
        new SchoolLink("l2", "checks", "https://forms.gle/abc", "Consent", NOW,
            SchoolLink.Status.PENDING, null, null)));

    assertThat(service.recentMessages(5)).extracting(SchoolNewsService.Message::title)
        .containsExactly("October Online Safety Newsletter",
            "Reception and Year 6 - Height and Weight");
  }

  @Test
  @DisplayName("asks for nothing more when there are no recent public emails")
  void noEmails() {
    when(documents.findPublishedBetween(any(), eq(List.of(SchoolSourceType.EMAIL)), any(), any()))
        .thenReturn(List.of());

    assertThat(service.recentMessages(5)).isEmpty();

    verify(links, never()).findBySourceDocumentIdIn(anyList());
  }
}
