package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Exercises the GitHub social-link renaming against a real MongoDB. Mongock is disabled in
 * tests, so the change unit is driven directly.
 */
class V017NameGithubSocialLinksTest extends AbstractIntegrationTest {

  private static final String COLLECTION = "social_medias";
  private static final String PERSONAL_URL = "https://github.com/simonrowe";
  private static final String SITE_URL = "https://github.com/simonjamesrowe";

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V017NameGithubSocialLinks changeUnit = new V017NameGithubSocialLinks();

  @BeforeEach
  @AfterEach
  void dropCollection() {
    mongoTemplate.getCollection(COLLECTION).drop();
  }

  @Test
  void namesBothGithubLinksDistinctly() {
    insertProductionShape();

    changeUnit.execution(mongoTemplate);

    assertThat(nameOf(PERSONAL_URL)).isEqualTo("GitHub — personal");
    assertThat(nameOf(SITE_URL)).isEqualTo("GitHub — this site");
  }

  @Test
  void leavesNonGithubLinksUntouched() {
    insertProductionShape();

    changeUnit.execution(mongoTemplate);

    assertThat(nameOf("https://www.linkedin.com/in/simon-rowe-2a94ab1/")).isEqualTo("Linkedin");
    assertThat(nameOf("https://twitter.com/rowe_simon")).isEqualTo("Simon Rowe - Twitter");
  }

  @Test
  void isIdempotentOnSecondRun() {
    insertProductionShape();
    changeUnit.execution(mongoTemplate);
    final List<Document> afterFirstRun = allDocuments();

    changeUnit.execution(mongoTemplate);

    assertThat(allDocuments()).isEqualTo(afterFirstRun);
  }

  @Test
  void leavesAlreadyCorrectNamesUnwritten() {
    mongoTemplate.getCollection(COLLECTION).insertOne(
        socialLink("github", "GitHub — personal", PERSONAL_URL));

    changeUnit.execution(mongoTemplate);

    assertThat(nameOf(PERSONAL_URL)).isEqualTo("GitHub — personal");
  }

  @Test
  void toleratesTrailingSlashOnTheStoredUrl() {
    mongoTemplate.getCollection(COLLECTION).insertOne(
        socialLink("github", "Personal Github Account", PERSONAL_URL + "/"));

    changeUnit.execution(mongoTemplate);

    assertThat(nameOf(PERSONAL_URL + "/")).isEqualTo("GitHub — personal");
  }

  @Test
  void ignoresUnknownGithubAccounts() {
    mongoTemplate.getCollection(COLLECTION).insertOne(
        socialLink("github", "Some other account", "https://github.com/someone-else"));

    changeUnit.execution(mongoTemplate);

    assertThat(nameOf("https://github.com/someone-else")).isEqualTo("Some other account");
  }

  @Test
  void rollbackUnsetsOnlyTheTwoKnownGithubNames() {
    insertProductionShape();
    changeUnit.execution(mongoTemplate);

    changeUnit.rollback(mongoTemplate);

    assertThat(documentFor(PERSONAL_URL).get("name")).isNull();
    assertThat(documentFor(SITE_URL).get("name")).isNull();
    assertThat(nameOf("https://twitter.com/rowe_simon")).isEqualTo("Simon Rowe - Twitter");
  }

  /** The four links as they exist in production, names included. */
  private void insertProductionShape() {
    mongoTemplate.getCollection(COLLECTION).insertMany(List.of(
        socialLink("github", "Personal Github Account", PERSONAL_URL),
        socialLink("github",
            "Public org for all repos that make up www.simonjamesrowe.com", SITE_URL),
        socialLink("linkedin", "Linkedin", "https://www.linkedin.com/in/simon-rowe-2a94ab1/"),
        socialLink("twitter", "Simon Rowe - Twitter", "https://twitter.com/rowe_simon")));
  }

  private Document socialLink(final String type, final String name, final String link) {
    return new Document()
        .append("type", type)
        .append("name", name)
        .append("link", link)
        .append("includeOnResume", Boolean.TRUE);
  }

  private Document documentFor(final String link) {
    return mongoTemplate.getCollection(COLLECTION).find(new Document("link", link)).first();
  }

  private String nameOf(final String link) {
    final Document document = documentFor(link);
    return document == null ? null : document.getString("name");
  }

  private List<Document> allDocuments() {
    return mongoTemplate.getCollection(COLLECTION)
        .find()
        .sort(new Document("link", 1))
        .into(new java.util.ArrayList<>());
  }
}
