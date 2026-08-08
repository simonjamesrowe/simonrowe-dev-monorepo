package com.simonrowe.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SourceNameResolverTest {

  @Mock private ContentSourceRepository sourceRepository;

  @InjectMocks private SourceNameResolver resolver;

  private static ContentSource source(
      String name, String baseUrl, ContentSource.SourceType type) {
    return new ContentSource(
        name.toLowerCase(), name, baseUrl, null, null, type,
        ContentSource.ScrapeStrategy.HTML_LISTING, true, null, null);
  }

  @Test
  void resolve_reusesTheNameOfSourceOnTheSameHost() {
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Tessl Blog", "https://tessl.io/blog", ContentSource.SourceType.BLOG)));

    assertThat(resolver.resolve("https://tessl.io/podcast/116")).isEqualTo("Tessl Blog");
  }

  @Test
  void resolve_ignoresWwwPrefixOnEitherSide() {
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Dan Vega", "https://www.danvega.dev/blog", ContentSource.SourceType.BLOG)));

    assertThat(resolver.resolve("https://danvega.dev/blog/some-post")).isEqualTo("Dan Vega");
  }

  @Test
  void resolve_mapsAliasedHostsOntoTheirCanonicalSource() {
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Claude Blog", "https://claude.com/blog", ContentSource.SourceType.BLOG)));

    assertThat(resolver.resolve("https://www.anthropic.com/news/claude-opus-4-7"))
        .isEqualTo("Claude Blog");
    assertThat(resolver.resolve("https://code.claude.com/docs/en/routines"))
        .isEqualTo("Claude Blog");
  }

  @Test
  void resolve_fallsBackToTheHostWhenNoSourceMatches() {
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Spring Blog", "https://spring.io/blog", ContentSource.SourceType.BLOG)));

    assertThat(resolver.resolve("https://blog.cloudflare.com/ai-code-review"))
        .isEqualTo("blog.cloudflare.com");
  }

  @Test
  void resolve_ignoresEventSourcesWhenNamingAnArticle() {
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Tessl Events", "https://tessl.io/events", ContentSource.SourceType.EVENTS),
        source("Tessl Blog", "https://tessl.io/blog", ContentSource.SourceType.BLOG)));

    assertThat(resolver.resolve("https://tessl.io/podcast/116")).isEqualTo("Tessl Blog");
  }

  @Test
  void resolve_fallsBackToTheHostWhenTwoSourcesShareIt() {
    // Ambiguity must not become an order-dependent guess: keeping the host is wrong
    // in a visible, fixable way, whereas picking the wrong source name is silent.
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Foo One", "https://shared.example/a", ContentSource.SourceType.BLOG),
        source("Foo Two", "https://shared.example/b", ContentSource.SourceType.NEWS)));

    assertThat(resolver.resolve("https://shared.example/post")).isEqualTo("shared.example");
  }

  @Test
  void resolve_returnsManualImportForAnUnparseableUrl() {
    assertThat(resolver.resolve("not a url at all")).isEqualTo("Manual Import");
  }

  @Test
  void hostOf_lowercasesAndStripsWww() {
    assertThat(SourceNameResolver.hostOf("https://WWW.Example.COM/x")).isEqualTo("example.com");
    assertThat(SourceNameResolver.hostOf("nonsense")).isNull();
    assertThat(SourceNameResolver.hostOf(null)).isNull();
  }
}
