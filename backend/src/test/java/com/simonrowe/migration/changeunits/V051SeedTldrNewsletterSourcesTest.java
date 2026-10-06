package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import com.simonrowe.aggregation.newsletter.NewsletterCandidate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

@ExtendWith(MockitoExtension.class)
class V051SeedTldrNewsletterSourcesTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private ContentSourceRepository sourceRepository;
  @Mock private IndexOperations indexOps;

  private final V051SeedTldrNewsletterSources changeUnit = new V051SeedTldrNewsletterSources();

  @Test
  void seedsOneEmailSourcePerEditionNamedAsItsSender() {
    when(mongoTemplate.indexOps(NewsletterCandidate.COLLECTION)).thenReturn(indexOps);
    when(sourceRepository.findByName(anyString())).thenReturn(Optional.empty());

    changeUnit.execution(mongoTemplate, sourceRepository);

    ArgumentCaptor<ContentSource> saved = ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository, times(4)).save(saved.capture());
    assertThat(saved.getAllValues()).extracting(ContentSource::name)
        .containsExactly("TLDR", "TLDR Dev", "TLDR Product", "TLDR AI");
    assertThat(saved.getAllValues()).allSatisfy(source -> {
      assertThat(source.scrapeStrategy())
          .isEqualTo(ContentSource.ScrapeStrategy.EMAIL_NEWSLETTER);
      assertThat(source.feedUrl()).isEqualTo("dan@tldrnewsletter.com");
      assertThat(source.sourceType()).isEqualTo(ContentSource.SourceType.NEWS);
      assertThat(source.active()).isTrue();
    });
  }

  @Test
  void createsUniqueUrlIndexSoEachStoryIsReadOnce() {
    when(mongoTemplate.indexOps(NewsletterCandidate.COLLECTION)).thenReturn(indexOps);

    V051SeedTldrNewsletterSources.createIndexes(mongoTemplate);

    ArgumentCaptor<IndexDefinition> indexes = ArgumentCaptor.forClass(IndexDefinition.class);
    verify(indexOps, times(2)).createIndex(indexes.capture());
    IndexDefinition url = indexes.getAllValues().getFirst();
    assertThat(url.getIndexKeys().keySet()).containsExactly("url");
    assertThat(url.getIndexOptions().getBoolean("unique")).isTrue();
    assertThat(url.getIndexOptions().getString("name"))
        .isEqualTo(V051SeedTldrNewsletterSources.URL_INDEX);
  }

  @Test
  void leavesAnExistingSourceAlone() {
    when(mongoTemplate.indexOps(NewsletterCandidate.COLLECTION)).thenReturn(indexOps);
    when(sourceRepository.findByName(anyString())).thenReturn(Optional.of(new ContentSource(
        "x", "TLDR", "https://tldr.tech", "dan@tldrnewsletter.com", null,
        ContentSource.SourceType.NEWS, ContentSource.ScrapeStrategy.EMAIL_NEWSLETTER,
        false, null, null, null)));

    changeUnit.execution(mongoTemplate, sourceRepository);

    verify(sourceRepository, never()).save(any());
  }

  @Test
  void editionsAreTheFourTldrNewsletters() {
    assertThat(V051SeedTldrNewsletterSources.EDITIONS).extracting(e -> e[0])
        .isEqualTo(List.of("TLDR", "TLDR Dev", "TLDR Product", "TLDR AI"));
  }
}
