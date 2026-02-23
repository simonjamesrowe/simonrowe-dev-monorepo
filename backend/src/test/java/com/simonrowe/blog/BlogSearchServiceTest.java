package com.simonrowe.blog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BlogSearchServiceTest {

  @Test
  void stripMarkdownRemovesCommonMarkdownSyntax() {
    String markdown = "# Heading\n**bold** and `code` and [link](http://example.com)";
    String stripped = BlogSearchService.stripMarkdown(markdown);
    assertThat(stripped).doesNotContain("#", "*", "`", "[", "]", "(", ")");
  }

  @Test
  void stripMarkdownHandlesNullContent() {
    String result = BlogSearchService.stripMarkdown(null);
    assertThat(result).isEmpty();
  }

  @Test
  void stripMarkdownHandlesPlainText() {
    String plain = "This is plain text without any markdown.";
    String result = BlogSearchService.stripMarkdown(plain);
    assertThat(result).isEqualTo("This is plain text without any markdown.");
  }
}
