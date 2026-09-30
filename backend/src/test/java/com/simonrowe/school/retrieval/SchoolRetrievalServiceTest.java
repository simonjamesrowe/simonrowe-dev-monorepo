package com.simonrowe.school.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * How a selected year group shapes a prose search.
 *
 * <p>The case behind it, from production on 2026-09-30: with Year 3 selected, "Year 3 spellings
 * for this week" returned the Year 4 and Year 6 spelling sheets ahead of the Year 3 list pasted in
 * that evening, and the assistant presented the Year 6 words as Year 3's.
 */
class SchoolRetrievalServiceTest {

  private SchoolVectorStore store;
  private SchoolRetrievalService service;
  private final List<SearchRequest> requests = new ArrayList<>();

  @BeforeEach
  void setUp() {
    store = mock(SchoolVectorStore.class);
    service = new SchoolRetrievalService(store);
  }

  private static Document chunk(final String id, final List<String> years) {
    return Document.builder().id(id).text(id)
        .metadata(Map.of("visibility", "PUBLIC", "yearGroups", years)).build();
  }

  private static Document untagged(final String id) {
    // A chunk written before year groups were recorded has no key at all.
    return Document.builder().id(id).text(id).metadata(Map.of("visibility", "PUBLIC")).build();
  }

  /** Answers the year-filtered search with {@code scoped} and the unfiltered one with broad. */
  private void answer(final List<Document> scoped, final List<Document> broad) {
    when(store.search(any())).thenAnswer(call -> {
      final SearchRequest request = call.getArgument(0);
      requests.add(request);
      return request.getFilterExpression().toString().contains("yearGroups") ? scoped : broad;
    });
  }

  @Test
  @DisplayName("with no year selected there is one search, filtered on tier alone")
  void noYearIsOneSearch() {
    answer(List.of(), List.of(chunk("y6-sheet", List.of("Year 6")), untagged("lunch")));

    final List<Document> hits = service.search("spellings", SchoolAudience.anonymous(), List.of());

    assertThat(hits).extracting(Document::getId).containsExactly("y6-sheet", "lunch");
    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().getFilterExpression().toString())
        .contains("visibility").doesNotContain("yearGroups");
  }

  @Test
  @DisplayName("the selected year's sources come first, even when less similar")
  void selectedYearComesFirst() {
    // The pasted Year 3 note scored 0.42; four other years' sheets scored 0.56.
    final Document note = chunk("y3-note", List.of("Year 3"));
    answer(List.of(note), List.of(chunk("y4-sheet", List.of("Year 4")), untagged("y3-page"),
        note));

    final List<Document> hits =
        service.search("Year 3 spellings", SchoolAudience.anonymous(), List.of("Year 3"));

    assertThat(hits).extracting(Document::getId).containsExactly("y3-note", "y3-page");
  }

  @Test
  @DisplayName("a source for other years only is dropped; whole-school and shared ones stay")
  void otherYearsAreDroppedWholeSchoolStays() {
    answer(List.of(), List.of(
        chunk("y6-sheet", List.of("Year 6")),
        chunk("shared", List.of("Year 3", "Year 6")),
        chunk("empty-tag", List.of()),
        untagged("newsletter")));

    final List<Document> hits =
        service.search("spellings", SchoolAudience.anonymous(), List.of("Year 3"));

    assertThat(hits).extracting(Document::getId)
        .containsExactly("shared", "empty-tag", "newsletter");
  }

  @Test
  @DisplayName("a scoped result not actually tagged for the year is not trusted")
  void scopedResultIsRechecked() {
    // The filter is a Lucene query string over a text field. If it ever matches loosely, the
    // result must still not reach the model looking like one of the selected year's.
    answer(List.of(chunk("y6-sheet", List.of("Year 6"))), List.of());

    assertThat(service.search("spellings", SchoolAudience.anonymous(), List.of("Year 3")))
        .isEmpty();
  }

  @Test
  @DisplayName("the merged list is capped and carries no duplicates")
  void mergedListIsCappedAndDeduplicated() {
    final List<Document> scoped = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      scoped.add(chunk("y3-" + i, List.of("Year 3")));
    }
    final List<Document> broad = new ArrayList<>(scoped);
    for (int i = 0; i < 10; i++) {
      broad.add(untagged("whole-" + i));
    }
    answer(scoped, broad);

    final List<Document> hits =
        service.search("anything", SchoolAudience.anonymous(), List.of("Year 3"));

    assertThat(hits).hasSize(8);
    assertThat(hits).extracting(Document::getId).doesNotHaveDuplicates()
        .startsWith("y3-0", "y3-1", "y3-2", "y3-3", "y3-4", "whole-0");
  }

  @Test
  @DisplayName("both searches keep the tier filter, and the unfiltered one reads wider")
  void bothSearchesAreTierFiltered() {
    answer(List.of(), List.of());

    service.search("anything", SchoolAudience.anonymous(), List.of("Year 3", "Not a year"));

    final ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(store, times(2)).search(captor.capture());
    assertThat(captor.getAllValues()).allSatisfy(request ->
        assertThat(request.getFilterExpression().toString()).contains("visibility"));
    final SearchRequest scoped = captor.getAllValues().getFirst();
    assertThat(scoped.getFilterExpression().toString())
        .contains("Year 3").doesNotContain("Not a year");
    assertThat(captor.getAllValues().get(1).getTopK()).isGreaterThan(scoped.getTopK());
  }

  @Test
  @DisplayName("year groups are read from a list, a single string, or nothing")
  void yearGroupsOfToleratesEveryShape() {
    assertThat(SchoolRetrievalService.yearGroupsOf(chunk("a", List.of("Year 1", "Year 2"))))
        .containsExactly("Year 1", "Year 2");
    assertThat(SchoolRetrievalService.yearGroupsOf(Document.builder().id("b").text("b")
        .metadata(Map.of("yearGroups", "Year 5")).build())).containsExactly("Year 5");
    assertThat(SchoolRetrievalService.yearGroupsOf(untagged("c"))).isEmpty();
  }
}
