package com.simonrowe.school.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.retrieval.SchoolAudience;
import com.simonrowe.school.retrieval.SchoolRetrievalService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code getRecentCommunications} hands the model.
 *
 * <p>Two things it must do that no other tool here can. It can say a window was <b>empty</b> —
 * an empty top-k from similarity search means "nothing was similar", never "nothing exists", so
 * until this existed the assistant had no way to tell a parent there was no newsletter last
 * week and instead produced the nearest old one. And it must never hand over a document it has
 * cut in half: an assistant reading a truncated newsletter cannot tell that it is reading a
 * fragment, so it reports that the newsletter does not mention something that was in the
 * paragraph after the cut.
 */
class SchoolToolsCommunicationsTest {

  private SchoolQueryService queries;
  private SchoolTools tools;

  @BeforeEach
  void setUp() {
    queries = mock(SchoolQueryService.class);
    tools = new SchoolTools(queries, mock(SchoolRetrievalService.class),
        SchoolAudience.anonymous(), List.of(), null, null, "https://term-time.simonrowe.dev");
  }

  @Test
  @DisplayName("an empty window is reported as a fact about the window")
  void emptyWindowIsAnswered() {
    when(queries.communicationsBetween(any(), any(), any())).thenReturn(List.of());

    final String result = tools.getRecentCommunications("2026-09-07", "2026-09-13");

    // Phrased as "the school published nothing", not "nothing was found". The difference is the
    // entire value of this tool: it licenses a definite no.
    assertThat(result).contains("published nothing between 2026-09-07 and 2026-09-13");
  }

  @Test
  @DisplayName("unreadable dates are refused rather than silently reinterpreted")
  void unparseableDatesAreRefused() {
    assertThat(tools.getRecentCommunications("last Monday", "2026-09-13"))
        .contains("could not be read");
  }

  @Test
  @DisplayName("every item is indexed, and the newsletter arrives whole")
  void indexesEverythingAndIncludesTheText() {
    when(queries.communicationsBetween(any(), any(), any())).thenReturn(List.of(
        newsletter(), letter("Enrichment Clubs reopen", "2026-09-11T15:09:37Z")));

    final String result = tools.getRecentCommunications("2026-09-07", "2026-09-13");

    assertThat(result)
        .contains("2 item(s) published between 2026-09-07 and 2026-09-13")
        .contains("\"Newsletter - 11th September 2026\"")
        .contains("\"Enrichment Clubs reopen\"")
        // The real address, so a parent can click through and check.
        .contains("https://www.kilmorieschool.co.uk/parentportal/newsletter/?id=163")
        .contains("The autumnal weather has certainly arrived")
        .doesNotContain("was not included");
  }

  @Test
  @DisplayName("an email's gmail pseudo-reference is never offered as a link")
  void emailRefsAreNotLinks() {
    when(queries.communicationsBetween(any(), any(), any()))
        .thenReturn(List.of(letter("Year 6 PGL", "2026-09-10T16:28:22Z")));

    // "gmail:msg-1" is meaningless to a reader, and the model will happily render anything it is
    // handed in a url= field as a markdown link.
    assertThat(tools.getRecentCommunications("2026-09-07", "2026-09-13"))
        .contains("url=\"\"")
        .doesNotContain("gmail:");
  }

  @Test
  @DisplayName("documents past the budget are named in the index and counted, never truncated")
  void overflowIsNamedRatherThanCut() {
    final List<SchoolDocument> many = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      many.add(bulky("Letter " + i));
    }
    when(queries.communicationsBetween(any(), any(), any())).thenReturn(many);

    final String result = tools.getRecentCommunications("2026-07-14", "2026-09-13");

    // Every one is listed by title and date, because the question underneath is often "did the
    // school send X" and that has to be answerable even when X's text did not fit.
    for (int i = 0; i < 12; i++) {
      assertThat(result).contains("\"Letter " + i + "\"");
    }
    assertThat(result).contains("was not included");
    // Whole documents only. A half-quoted letter reads to the model as a complete one.
    assertThat(result.split("<<<END SOURCE>>>", -1).length - 1)
        .isEqualTo(result.split("<<<SOURCE ", -1).length - 1);
  }

  @Test
  @DisplayName("one oversized document is still returned rather than dropped for being long")
  void oneOversizedDocumentIsStillReturned() {
    // Otherwise a school that publishes one very long newsletter gets an index with no text at
    // all under it, and the assistant reports that it cannot read the only thing there is.
    when(queries.communicationsBetween(any(), any(), any()))
        .thenReturn(List.of(bulky("Annual report")));

    assertThat(tools.getRecentCommunications("2026-09-07", "2026-09-13"))
        .contains("<<<SOURCE ")
        .doesNotContain("was not included");
  }

  private SchoolDocument newsletter() {
    return new SchoolDocument(
        "doc-newsletter", SchoolSourceType.WEBSITE_PAGE,
        "https://www.kilmorieschool.co.uk/parentportal/newsletter/?id=163",
        "Newsletter - 11th September 2026",
        "Friday 11th September 2026 Dear Parents and Carers, The autumnal weather has certainly "
            + "arrived this week, reminding us that the seasons are changing.",
        Instant.parse("2026-09-11T00:00:00Z"), Instant.parse("2026-09-11T15:22:35Z"),
        Visibility.PUBLIC, null, null, "simon", Instant.parse("2026-09-11T15:22:35Z"), false,
        List.of(), "hash", null);
  }

  private SchoolDocument letter(final String title, final String at) {
    return new SchoolDocument(
        "doc-" + title.hashCode(), SchoolSourceType.EMAIL, "gmail:msg-1", title,
        "Dear Parents and Guardians, " + title, Instant.parse(at), Instant.parse(at),
        Visibility.PUBLIC, null, null, "simon", Instant.parse(at), false, List.of(), "hash", null);
  }

  private SchoolDocument bulky(final String title) {
    return new SchoolDocument(
        "doc-" + title.hashCode(), SchoolSourceType.EMAIL, "gmail:" + title.hashCode(), title,
        "x".repeat(4000), Instant.parse("2026-09-01T09:00:00Z"),
        Instant.parse("2026-09-01T09:00:00Z"), Visibility.PUBLIC, null, null, "simon",
        Instant.parse("2026-09-01T09:00:00Z"), false, List.of(), "hash", null);
  }
}
