package com.simonrowe.school.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.ingest.DocumentDateReader;
import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.ingest.SchoolDocumentWriter;
import com.simonrowe.school.ingest.SchoolEventWriter;
import com.simonrowe.school.ingest.SchoolIngestService;
import com.simonrowe.school.ingest.SchoolLinkFilter;
import com.simonrowe.school.ingest.SchoolPdfExtractor;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins which tier a fetched link lands in.
 *
 * <p>This was a live fault, and it is the kind that produces no error anywhere. The school moved
 * its weekly newsletter out of the mail body and onto its parent portal on 11 September 2026, so
 * the email became a covering sentence and a link. Ingest followed the link correctly, extracted
 * the page correctly, and then filed it {@code RESTRICTED} because it inherited the tier of the
 * email that carried it — leaving the newsletter invisible to every visitor of the public chat
 * until somebody approved it by hand. Asked what was in last week's newsletter, Term Time
 * answered from a copy dated five weeks earlier and reported that one as the latest.
 *
 * <p>The rule these tests hold in place is that the tier follows the CONTENT. A page the school
 * published on its own website is public wherever it was discovered, because
 * {@code SchoolIngestService.ingestWebsite} already stores every other page on that host that
 * way; anything else still inherits, so a link to a third-party portal cannot become public by
 * being fetched.
 */
class SchoolLinkFetcherTierTest {

  private static final String NEWSLETTER =
      "https://www.kilmorieschool.co.uk/parentportal/newsletter/?id=163";

  private final SchoolLinkFetcher fetcher = new SchoolLinkFetcher(
      mock(SchoolLinkRepository.class),
      mock(SchoolDocumentRepository.class),
      mock(SchoolDocumentWriter.class),
      mock(SchoolPdfExtractor.class),
      mock(SchoolAttachmentStore.class),
      mock(SchoolIngestService.class),
      mock(SchoolEventExtractor.class),
      mock(SchoolEventWriter.class),
      mock(DocumentDateReader.class),
      // The real filter, not a mock. The whole design of the fix is that one predicate decides
      // both "may ingest follow this on its own" and "is this the school's own published page",
      // so a mock here would assert the plumbing and prove nothing about the rule.
      new SchoolLinkFilter(properties()));

  @Test
  @DisplayName("the school's own newsletter is public even though a restricted email carried it")
  void schoolNewsletterIsPublic() {
    assertThat(fetcher.tierFor(NEWSLETTER, email(Visibility.RESTRICTED)))
        .isEqualTo(Visibility.PUBLIC);
  }

  @Test
  @DisplayName("anything else inherits the tier of the document it was found in")
  void everythingElseInherits() {
    // A booking form, a third-party portal, a fundraising page: fetching one is not publishing
    // it, and a single click must never be able to put an arbitrary fetched page on the open
    // site. This is the case the inheritance rule was written for and it is unchanged.
    assertThat(fetcher.tierFor(
        "https://forms.cloud.microsoft/e/S5MVe36BRF", email(Visibility.RESTRICTED)))
        .isEqualTo(Visibility.RESTRICTED);
    assertThat(fetcher.tierFor(
        "https://www.justgiving.com/campaign/kilmoriebighalf2026", email(Visibility.RESTRICTED)))
        .isEqualTo(Visibility.RESTRICTED);
  }

  @Test
  @DisplayName("a newsletter path on somebody else's host is not the school's newsletter")
  void impostorHostIsNotPublic() {
    // The host is checked before the path, so a lookalike cannot buy its way into the public
    // tier by putting /newsletter/ in its address.
    assertThat(fetcher.tierFor(
        "https://evil.example.com/newsletter/?id=163", email(Visibility.RESTRICTED)))
        .isEqualTo(Visibility.RESTRICTED);
    assertThat(fetcher.tierFor(
        "https://notkilmorieschool.co.uk/newsletter/", email(Visibility.RESTRICTED)))
        .isEqualTo(Visibility.RESTRICTED);
  }

  @Test
  @DisplayName("a page on the school's host that is not a newsletter still inherits")
  void schoolHostAloneIsNotEnough() {
    // Deliberately narrow, and this is the reason: the parent portal also serves per-family
    // pages. Widening the rule to the whole school domain would start publishing those.
    assertThat(fetcher.tierFor(
        "https://www.kilmorieschool.co.uk/parentportal/child/?id=99", email(Visibility.RESTRICTED)))
        .isEqualTo(Visibility.RESTRICTED);
  }

  @Test
  @DisplayName("an orphaned link falls back to restricted, never to public")
  void missingParentFailsClosed() {
    assertThat(fetcher.tierFor("https://forms.cloud.microsoft/e/S5MVe36BRF", null))
        .isEqualTo(Visibility.RESTRICTED);
  }

  private SchoolDocument email(final Visibility visibility) {
    return new SchoolDocument(
        "doc-1", SchoolSourceType.EMAIL, "msg-1", "Weekly Newsletter",
        "Please find this week's newsletter below:", Instant.parse("2026-09-11T15:03:59Z"),
        Instant.parse("2026-09-11T15:22:00Z"), visibility, null, null, null, null, false,
        List.of(), "hash", null);
  }

  private static SchoolProperties properties() {
    return new SchoolProperties(true, LocalDate.of(2026, 7, 1),
        List.of("kilmorie.lewisham.sch.uk"), List.of(), null,
        "https://www.kilmorieschool.co.uk", null, 0, null, null, 0, null, null);
  }
}
