package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.school.SchoolProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the parent portal's newsletter list.
 *
 * <p>The fixture is the real list markup, so a change in how the CMS lays it out shows up here
 * rather than as a newsletter silently missing from the page.
 */
class SchoolWebsiteCrawlerNewslettersTest {

  private static final String BASE = "https://www.kilmorieschool.co.uk";
  private static final String EMAIL_FORM =
      "https://www\\.kilmorieschool\\.co\\.uk/parentportal/newsletter/\\?id=\\d+";

  private static String fixture() throws IOException {
    try (InputStream in = SchoolWebsiteCrawlerNewslettersTest.class
        .getResourceAsStream("/school/newsletter-list.html")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("lists every issue in page order with its id, title and date")
  void readsTheList() throws IOException {
    final List<SchoolWebsiteCrawler.PortalNewsletter> issues =
        SchoolWebsiteCrawler.parseNewsletterList(fixture(), BASE);

    assertThat(issues).extracting(SchoolWebsiteCrawler.PortalNewsletter::id)
        .containsExactly("171", "170", "169", "167", "166", "165", "164", "99");
    assertThat(issues.get(0)).isEqualTo(new SchoolWebsiteCrawler.PortalNewsletter("171",
        BASE + "/parentportal/newsletter/?id=171",
        "Kilmorie Primary School Newsletter - 23rd October 2026", LocalDate.of(2026, 10, 23)));
  }

  @Test
  @DisplayName("builds each address in the form the emails link to, so both share a document")
  void usesTheEmailAddressForm() throws IOException {
    // The list itself links to ?action=list&itemid=N. Storing that form would give every
    // emailed newsletter a second document with the same text.
    assertThat(SchoolWebsiteCrawler.parseNewsletterList(fixture(), BASE))
        .extracting(SchoolWebsiteCrawler.PortalNewsletter::url)
        .allMatch(url -> url.matches(EMAIL_FORM));
  }

  @Test
  @DisplayName("reads the 25 September issue, the one that was never emailed")
  void readsTheUnemailedIssue() throws IOException {
    assertThat(SchoolWebsiteCrawler.parseNewsletterList(fixture(), BASE))
        .anySatisfy(issue -> {
          assertThat(issue.id()).isEqualTo("165");
          assertThat(issue.date()).isEqualTo(LocalDate.of(2026, 9, 25));
        });
  }

  @Test
  @DisplayName("accepts \"Sept\" as well as \"Sep\"")
  void acceptsSept() throws IOException {
    assertThat(SchoolWebsiteCrawler.parseNewsletterList(fixture(), BASE))
        .filteredOn(issue -> issue.id().equals("99"))
        .extracting(SchoolWebsiteCrawler.PortalNewsletter::date)
        .containsExactly(LocalDate.of(2025, 9, 12));
  }

  @Test
  @DisplayName("skips an item with no id, and items from other portal lists")
  void skipsWhatIsNotNewsletter() throws IOException {
    assertThat(SchoolWebsiteCrawler.parseNewsletterList(fixture(), BASE))
        .extracting(SchoolWebsiteCrawler.PortalNewsletter::id)
        .doesNotContain("", "5");
  }

  @Test
  @DisplayName("returns nothing when the list cannot be read")
  void nothingWhenUnreadable() {
    final SchoolProperties properties = new SchoolProperties(
        true, null, null, null, null, BASE, null, 0, null, null, null, 0, null, null);
    final SchoolWebsiteCrawler crawler =
        new SchoolWebsiteCrawler(properties, new SchoolLinkFilter(properties)) {
          @Override
          String fetchText(final String url) {
            return null;
          }
        };

    assertThat(crawler.listNewsletters()).isEmpty();
  }
}
