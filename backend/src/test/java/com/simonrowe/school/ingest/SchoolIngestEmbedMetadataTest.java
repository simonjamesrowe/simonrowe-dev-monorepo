package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.chat.StaffDirectory;
import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.SchoolSyncStateRepository;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.retrieval.SchoolVectorStore;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * What an indexed chunk says about its document.
 *
 * <p>The year ticked on a pasted note was recorded on the document and nowhere else. Retrieval
 * reads Elasticsearch, not Mongo, so the search could not favour it and the assistant could not
 * see it: a Year 3 spelling list reached the model as an anonymous list of words.
 */
class SchoolIngestEmbedMetadataTest {

  @Test
  @DisplayName("every chunk carries its document's year groups")
  void chunksCarryYearGroups() {
    final SchoolVectorStore vectorStore = mock(SchoolVectorStore.class);
    final SchoolProperties properties = new SchoolProperties(
        true, null, List.of(), List.of(), null, null, null, 0, null, null, null, 0L, null,
        List.of());
    final SchoolIngestService service = new SchoolIngestService(
        properties, mock(CalendarFeedClient.class), mock(SchoolWebsiteCrawler.class),
        mock(SchoolDocumentWriter.class), mock(SchoolEventWriter.class),
        mock(SchoolSyncStateRepository.class), mock(StaffDirectory.class), vectorStore,
        TokenTextSplitter.builder().withChunkSize(20).withMinChunkSizeChars(10).build(),
        mock(SchoolPdfExtractor.class), mock(SchoolUsageRecorder.class),
        mock(SchoolEventExtractor.class), mock(DocumentDateReader.class),
        new SchoolLinkFilter(properties));
    final SchoolDocument note = new SchoolDocument(
        "note-1", SchoolSourceType.PASTED_NOTE, "paste:abc",
        "Prefixes 'un', 'dis' - Autumn 1 Week 3",
        "Autumn 1 Week 3. Prefixes un and dis. unhappy unusual disagree disobey displease. "
            + "Words from the statutory list: answer appear. ".repeat(4),
        Instant.parse("2026-09-30T18:33:58Z"), Instant.now(), Visibility.PUBLIC, null, null,
        null, null, false, List.of("Year 3"), "hash", null);

    service.embed(note);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<List<Document>> added = ArgumentCaptor.forClass(List.class);
    verify(vectorStore).add(added.capture());
    assertThat(added.getValue()).hasSizeGreaterThan(1).allSatisfy(chunk ->
        assertThat(chunk.getMetadata().get("yearGroups")).isEqualTo(List.of("Year 3")));
  }
}
