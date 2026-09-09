package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The write path every source funnels through.
 *
 * <p>Three properties matter here and none of them fail loudly when broken: an unchanged
 * document must not be reported as changed (or every crawl re-embeds the whole site), a human's
 * decision must survive a re-ingest (or an edited newsletter silently re-enters the approval
 * queue, or worse leaves the public tier), and a corrected date must be written even when the
 * text is identical.
 */
class SchoolDocumentWriterTest {

  private static final Instant PUBLISHED = Instant.parse("2026-07-16T00:00:00Z");

  private SchoolDocumentRepository repository;
  private SchoolDocumentWriter writer;

  @BeforeEach
  void setUp() {
    repository = mock(SchoolDocumentRepository.class);
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    writer = new SchoolDocumentWriter(repository);
  }

  private SchoolDocumentWriter.WriteResult write(final String body, final Instant publishedAt) {
    return writer.write(SchoolSourceType.WEBSITE_PAGE, "https://example.test/page", "Title",
        body, publishedAt, List.of(), Visibility.PUBLIC);
  }

  @Test
  @DisplayName("a document nobody has seen is stored and reported as changed")
  void newDocumentIsChanged() {
    when(repository.findById(any())).thenReturn(Optional.empty());

    final SchoolDocumentWriter.WriteResult result = write("Term starts on 2 September.", PUBLISHED);

    assertThat(result.changed()).isTrue();
    assertThat(result.document().visibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(result.document().publishedAt()).isEqualTo(PUBLISHED);
  }

  @Test
  @DisplayName("identical text is reported unchanged, so a crawl does not re-embed the whole site")
  void unchangedTextIsNotReportedAsChanged() {
    final SchoolDocumentWriter.WriteResult first = write("Same words.", PUBLISHED);
    when(repository.findById(any())).thenReturn(Optional.of(first.document()));

    assertThat(write("Same words.", PUBLISHED).changed()).isFalse();
  }

  @Test
  @DisplayName("a corrected date is written even though the text is identical")
  void staleDateIsCorrectedInPlace() {
    // Website PDFs were stamped with the crawl time. Because an unchanged document returned
    // early, no later crawl could ever have corrected those dates - they were frozen wrong.
    final SchoolDocumentWriter.WriteResult first = write("Same words.", Instant.EPOCH);
    when(repository.findById(any())).thenReturn(Optional.of(first.document()));

    final SchoolDocumentWriter.WriteResult second = write("Same words.", PUBLISHED);

    assertThat(second.document().publishedAt()).isEqualTo(PUBLISHED);
    // Still unchanged: the chunks are identical, so re-embedding would be pure cost.
    assertThat(second.changed()).isFalse();
  }

  @Test
  @DisplayName("a null incoming date never overwrites a date already known")
  void nullDateDoesNotOverwrite() {
    final SchoolDocumentWriter.WriteResult first = write("Same words.", PUBLISHED);
    when(repository.findById(any())).thenReturn(Optional.of(first.document()));

    assertThat(write("Same words.", null).document().publishedAt()).isEqualTo(PUBLISHED);
  }

  @Test
  @DisplayName("changed text carries every human decision forward")
  void changedTextKeepsHumanDecisions() {
    // The whole point of the approval model: re-ingesting an edited newsletter must not drop it
    // back out of the public tier, nor forget who approved it.
    final SchoolDocument approved = write("Old words.", PUBLISHED).document()
        .withApproval("simon", Instant.EPOCH, true);
    when(repository.findById(any())).thenReturn(Optional.of(approved));

    final SchoolDocumentWriter.WriteResult result = write("New words entirely.", PUBLISHED);

    assertThat(result.changed()).isTrue();
    assertThat(result.document().body()).isEqualTo("New words entirely.");
    assertThat(result.document().visibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(result.document().approvedBy()).isEqualTo("simon");
  }

  @Test
  @DisplayName("a decline survives a re-ingest, so a rejected letter cannot creep back")
  void declineSurvivesReIngest() {
    final SchoolDocument declined =
        write("Old words.", PUBLISHED).document().withDeclined(Instant.EPOCH);
    when(repository.findById(any())).thenReturn(Optional.of(declined));

    final SchoolDocument result = write("New words entirely.", PUBLISHED).document();

    assertThat(result.declinedAt()).isEqualTo(Instant.EPOCH);
    assertThat(result.needsDecision()).isFalse();
  }

  @Test
  @DisplayName("existingPublishedAt reports a stored date, and nothing for an unknown document")
  void existingPublishedAtReadsTheStoredDate() {
    final SchoolDocument stored = write("Words.", PUBLISHED).document();
    when(repository.findById(any())).thenReturn(Optional.of(stored));
    assertThat(writer.existingPublishedAt(SchoolSourceType.WEBSITE_PAGE, "https://example.test/page"))
        .contains(PUBLISHED);

    when(repository.findById(any())).thenReturn(Optional.empty());
    assertThat(writer.existingPublishedAt(SchoolSourceType.WEBSITE_PAGE, "https://nope.test"))
        .isEmpty();
  }

  @Test
  @DisplayName("the same text from two sources is two documents, not one")
  void identityIncludesTheSource() {
    // Identity is (sourceType, sourceRef). A newsletter reached by both crawl and email link is
    // legitimately two records with two tiers; collapsing them would let one leak the other.
    when(repository.findById(any())).thenReturn(Optional.empty());

    final SchoolDocument fromWeb = write("Shared words.", PUBLISHED).document();
    final SchoolDocument fromMail = writer.write(SchoolSourceType.EMAIL, "gmail:1", "Title",
        "Shared words.", PUBLISHED, List.of(), Visibility.RESTRICTED).document();

    assertThat(fromWeb.id()).isNotEqualTo(fromMail.id());
    assertThat(fromMail.visibility()).isEqualTo(Visibility.RESTRICTED);
  }

  @Test
  @DisplayName("save persists without inventing a change decision")
  void savePersists() {
    final SchoolDocument doc = write("Words.", PUBLISHED).document();
    org.mockito.Mockito.reset(repository);

    writer.save(doc);

    verify(repository).save(doc);
  }

  @Test
  @DisplayName("an unchanged document with an unchanged date is not written at all")
  void unchangedDocumentIsNotRewritten() {
    final SchoolDocumentWriter.WriteResult first = write("Same words.", PUBLISHED);
    org.mockito.Mockito.reset(repository);
    when(repository.findById(any())).thenReturn(Optional.of(first.document()));

    writer.write(SchoolSourceType.WEBSITE_PAGE, "https://example.test/page", "Title",
        "Same words.", PUBLISHED, List.of(), Visibility.PUBLIC);

    verify(repository, never()).save(any());
  }
}
