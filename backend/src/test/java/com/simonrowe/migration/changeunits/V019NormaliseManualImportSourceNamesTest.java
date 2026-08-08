package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.SourceNameResolver;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class V019NormaliseManualImportSourceNamesTest extends AbstractIntegrationTest {

  @MockitoBean private SourceNameResolver sourceNameResolver;
  @Autowired private MongoTemplate mongoTemplate;

  private final V019NormaliseManualImportSourceNames changeUnit =
      new V019NormaliseManualImportSourceNames();

  @BeforeEach
  @AfterEach
  void clearCollection() {
    mongoTemplate.getCollection("aggregated_articles").deleteMany(new Document());
  }

  private void insert(String sourceName, String originalUrl) {
    mongoTemplate.getCollection("aggregated_articles").insertOne(new Document()
        .append("sourceName", sourceName)
        .append("originalUrl", originalUrl)
        .append("title", "Some Title")
        .append("visible", true));
  }

  private String sourceNameOf(String originalUrl) {
    Document found = mongoTemplate.getCollection("aggregated_articles")
        .find(new Document("originalUrl", originalUrl)).first();
    return found == null ? null : found.getString("sourceName");
  }

  @Test
  void rewritesSourceNamesThatResolveToKnownSource() {
    insert("tessl.io", "https://tessl.io/podcast/116");
    insert("anthropic.com", "https://www.anthropic.com/news/claude-opus-4-7");
    when(sourceNameResolver.resolve("https://tessl.io/podcast/116"))
        .thenReturn("Tessl Blog");
    when(sourceNameResolver.resolve("https://www.anthropic.com/news/claude-opus-4-7"))
        .thenReturn("Claude Blog");

    changeUnit.execution(mongoTemplate, sourceNameResolver);

    assertThat(sourceNameOf("https://tessl.io/podcast/116")).isEqualTo("Tessl Blog");
    assertThat(sourceNameOf("https://www.anthropic.com/news/claude-opus-4-7"))
        .isEqualTo("Claude Blog");
  }

  @Test
  void leavesArticlesWhoseHostMatchesNoKnownSource() {
    insert("blog.cloudflare.com", "https://blog.cloudflare.com/ai-code-review");
    when(sourceNameResolver.resolve("https://blog.cloudflare.com/ai-code-review"))
        .thenReturn("blog.cloudflare.com");

    changeUnit.execution(mongoTemplate, sourceNameResolver);

    assertThat(sourceNameOf("https://blog.cloudflare.com/ai-code-review"))
        .isEqualTo("blog.cloudflare.com");
  }

  @Test
  void leavesArticlesAlreadyAttributedToTheirSource() {
    insert("Spring Blog", "https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA");
    when(sourceNameResolver.resolve("https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA"))
        .thenReturn("Spring Blog");

    changeUnit.execution(mongoTemplate, sourceNameResolver);

    assertThat(sourceNameOf("https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA"))
        .isEqualTo("Spring Blog");
  }

  @Test
  void isNoOpOnSecondRun() {
    insert("tessl.io", "https://tessl.io/podcast/116");
    when(sourceNameResolver.resolve("https://tessl.io/podcast/116"))
        .thenReturn("Tessl Blog");

    changeUnit.execution(mongoTemplate, sourceNameResolver);
    changeUnit.execution(mongoTemplate, sourceNameResolver);

    assertThat(sourceNameOf("https://tessl.io/podcast/116")).isEqualTo("Tessl Blog");
    List<Document> all = mongoTemplate.getCollection("aggregated_articles")
        .find().into(new ArrayList<>());
    assertThat(all).hasSize(1);
  }
}
