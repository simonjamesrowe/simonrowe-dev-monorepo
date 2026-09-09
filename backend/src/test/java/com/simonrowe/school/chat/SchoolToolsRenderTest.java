package com.simonrowe.school.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.school.retrieval.SchoolAudience;
import com.simonrowe.school.retrieval.SchoolRetrievalService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * What the model actually receives from a prose search.
 *
 * <p>Exists because of a real regression: the chunk was assembled with
 * {@code "…%s" + "…".formatted(args)}, and {@code .formatted} binds to the second literal only.
 * Every result reached the model as literal {@code %s} with no document text in it, and nothing
 * anywhere errored — the assistant simply stopped being able to answer from prose.
 */
class SchoolToolsRenderTest {

  private static final String BASE_URL = "https://term-time.simonrowe.dev";

  private SchoolTools toolsReturning(final List<Document> hits) {
    final SchoolRetrievalService retrieval = mock(SchoolRetrievalService.class);
    org.mockito.Mockito.when(retrieval.search(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any())).thenReturn(hits);
    return new SchoolTools(mock(SchoolQueryService.class), retrieval,
        SchoolAudience.anonymous(), List.of(), null, null, BASE_URL);
  }

  @Test
  @DisplayName("the chunk carries the document text, not a format placeholder")
  void chunkCarriesTheText() {
    final Document hit = new Document("Lunches are provided by Taylor Shaw from September.",
        Map.of("title", "New school lunch menu",
            "publishedAt", "2026-07-16T00:00:00Z",
            "sourceType", "PDF",
            "sourceRef", "https://www.kilmorieschool.co.uk/menu.pdf"));

    final String rendered = toolsReturning(List.of(hit)).searchSchoolInformation("lunch");

    assertThat(rendered).contains("Lunches are provided by Taylor Shaw from September.");
    assertThat(rendered).contains("New school lunch menu");
    assertThat(rendered).doesNotContain("%s");
  }

  @Test
  @DisplayName("a real web address is offered as the source url")
  void realUrlIsOffered() {
    final Document hit = new Document("Autumn timetable.",
        Map.of("title", "Enrichment", "publishedAt", "x", "sourceType", "PDF",
            "sourceRef", "https://www.kilmorieschool.co.uk/enrichment.pdf"));

    assertThat(toolsReturning(List.of(hit)).searchSchoolInformation("clubs"))
        .contains("url=\"https://www.kilmorieschool.co.uk/enrichment.pdf\"");
  }

  @Test
  @DisplayName("an email pseudo-ref is never offered as a link")
  void emailPseudoRefIsNeverLinked() {
    // `gmail:<id>:<attachmentId>` is not an address a reader can follow.
    final Document hit = new Document("Homework books next week.",
        Map.of("title", "Homework", "publishedAt", "x", "sourceType", "EMAIL",
            "sourceRef", "gmail:18f2c:0.1"));

    final String rendered = toolsReturning(List.of(hit)).searchSchoolInformation("homework");

    assertThat(rendered).contains("url=\"\"");
    assertThat(rendered).doesNotContain("gmail:18f2c");
  }

  @Test
  @DisplayName("a public email attachment is offered by its served address")
  void publicAttachmentIsOffered() {
    final Document hit = new Document("Autumn menu.",
        Map.of("title", "Menu", "publishedAt", "x", "sourceType", "PDF",
            "sourceRef", "gmail:18f2c:0.1",
            "attachmentUrl", "/api/school/attachments/abc123"));

    assertThat(toolsReturning(List.of(hit)).searchSchoolInformation("menu"))
        .contains("url=\"" + BASE_URL + "/api/school/attachments/abc123\"");
  }

  @Test
  @DisplayName("nothing found says so plainly")
  void nothingFound() {
    assertThat(toolsReturning(List.of()).searchSchoolInformation("anything"))
        .isEqualTo("Nothing in the school communications covers that.");
  }

  @Test
  @DisplayName("a stored attachment path is rendered as an absolute first-party URL")
  void attachmentPathIsMadeAbsolute() {
    // The bug this pins: the model was handed the bare path, had to supply an origin to write
    // a markdown link, and chose the school's own domain because that is what the rest of the
    // answer cites. Every PDF citation resolved to kilmorieschool.co.uk and 404'd.
    final Document hit = new Document("Week 1 menu.",
        Map.of("title", "Lunch menu", "publishedAt", "x", "sourceType", "PDF",
            "sourceRef", "gmail:abc:1",
            "attachmentUrl", "/api/school/attachments/abc123"));

    final String rendered = toolsReturning(List.of(hit)).searchSchoolInformation("lunch");

    assertThat(rendered).contains("url=\"" + BASE_URL + "/api/school/attachments/abc123\"");
    assertThat(rendered).doesNotContain("url=\"/api/");
  }

  @Test
  @DisplayName("an attachment URL already absolute in an older chunk is left alone")
  void alreadyAbsoluteAttachmentIsUntouched() {
    // Chunks written before the origin moved to render time carry a full URL. Prefixing again
    // would produce a doubled origin, and unchanged content never re-embeds to correct it.
    final Document hit = new Document("Week 1 menu.",
        Map.of("title", "Lunch menu", "publishedAt", "x", "sourceType", "PDF",
            "sourceRef", "gmail:abc:1",
            "attachmentUrl", "https://elsewhere.example/api/school/attachments/abc123"));

    assertThat(toolsReturning(List.of(hit)).searchSchoolInformation("lunch"))
        .contains("url=\"https://elsewhere.example/api/school/attachments/abc123\"");
  }
}
