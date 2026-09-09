package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.school.SchoolProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchoolLinkFilterTest {

  private final SchoolLinkFilter filter = new SchoolLinkFilter(new SchoolProperties(
      true, null, List.of(), List.of(), null, "https://www.kilmorieschool.co.uk", null,
      0, null, null, 0L, null));

  @Test
  @DisplayName("the school homepage is footer boilerplate and is never offered")
  void bareHomepageIsNotOffered() {
    // It is in the signature block of essentially every school email, and the website crawl
    // already reads it. Offering it makes the queue one repeated row per message.
    assertThat(filter.isWorthOffering("https://www.kilmorieschool.co.uk")).isFalse();
    assertThat(filter.isWorthOffering("https://www.kilmorieschool.co.uk/")).isFalse();
    assertThat(filter.isWorthOffering("http://kilmorieschool.co.uk/")).isFalse();
  }

  @Test
  @DisplayName("a deep link into the school site IS offered")
  void deepSchoolLinkIsOffered() {
    // This is the case the queue exists for: a specific letter or timetable.
    assertThat(filter.isWorthOffering(
        "https://www.kilmorieschool.co.uk/attachments/download.asp?file=819")).isTrue();
    assertThat(filter.isWorthOffering(
        "https://www.kilmorieschool.co.uk/enrichment")).isTrue();
  }

  @Test
  @DisplayName("unsubscribe and preference links are never offered")
  void listManagementIsNotOffered() {
    assertThat(filter.isWorthOffering("https://example.org/unsubscribe?u=123")).isFalse();
    assertThat(filter.isWorthOffering("https://example.org/manage-subscription")).isFalse();
    assertThat(filter.isWorthOffering("https://example.org/privacy-policy")).isFalse();
  }

  @Test
  @DisplayName("tracking and social hosts are never offered")
  void trackingHostsAreNotOffered() {
    // Following one of these tells the sender's analytics the message was opened — a side
    // effect this feature must never cause.
    assertThat(filter.isWorthOffering("https://click.list-manage.com/track/abc")).isFalse();
    assertThat(filter.isWorthOffering("https://www.facebook.com/kilmorie")).isFalse();
  }

  @Test
  @DisplayName("an ordinary third-party document link is offered")
  void thirdPartyDocumentIsOffered() {
    assertThat(filter.isWorthOffering("https://example.org/autumn-menu.pdf")).isTrue();
  }

  @Test
  @DisplayName("junk is rejected rather than throwing")
  void junkIsRejected() {
    assertThat(filter.isWorthOffering(null)).isFalse();
    assertThat(filter.isWorthOffering("")).isFalse();
    assertThat(filter.isWorthOffering("not a url at all")).isFalse();
  }

  @Test
  @DisplayName("a newsletter on the school's own site is followed without waiting for a human")
  void schoolNewslettersAreAutoFetched() {
    // The one carve-out from "record links, never follow them". The website crawl already reads
    // this host wholesale, so following one reaches nobody the crawler is not already reaching.
    assertThat(filter.isAutoFetchable(
        "https://www.kilmorieschool.co.uk/parentportal/newsletter/?id=162")).isTrue();
    assertThat(filter.isAutoFetchable(
        "https://kilmorieschool.co.uk/newsletters/autumn-2026")).isTrue();
  }

  @Test
  @DisplayName("nothing else is ever auto-fetched, however harmless it looks")
  void everythingElseStillWaitsForApproval() {
    // A newsletter path on somebody else's host is the obvious way to smuggle a fetch past a
    // path-only check, so the host is tested first and the path only afterwards.
    assertThat(filter.isAutoFetchable(
        "https://evil.example.com/newsletter/?id=1")).isFalse();
    assertThat(filter.isAutoFetchable(
        "https://www.eventbrite.co.uk/e/starting-secondary-school-tickets")).isFalse();
    assertThat(filter.isAutoFetchable(
        "https://www.kilmorieschool.co.uk/parentportal/statements/?child=7")).isFalse();
    assertThat(filter.isAutoFetchable("https://www.kilmorieschool.co.uk/")).isFalse();
  }
}
