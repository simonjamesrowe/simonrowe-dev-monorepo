package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.school.SchoolProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchoolLinkFilterTest {

  private final SchoolLinkFilter filter = new SchoolLinkFilter(new SchoolProperties(
      true, null, List.of(), List.of(), null, "https://www.kilmorieschool.co.uk", null,
      0, null, null, 0L, null, null));

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

  @Test
  @DisplayName("a page on the school's own site may be crawled")
  void schoolPagesAreCrawlable() {
    assertThat(filter.isCrawlableWebsitePage(
        "https://www.kilmorieschool.co.uk/year-six-home-learning")).isTrue();
    assertThat(filter.isCrawlableWebsitePage(
        "https://www.kilmorieschool.co.uk/page/?title=Home+Learning&pid=158")).isTrue();
    // www-less and subdomain forms are the same site.
    assertThat(filter.isCrawlableWebsitePage(
        "https://kilmorieschool.co.uk/year-six")).isTrue();
  }

  @Test
  @DisplayName("another domain is NEVER crawled, however the link got there")
  void otherDomainsAreNeverCrawled() {
    // Not hypothetical, and not an edge case: /year-six-home-learning links to four external
    // research sites for the children's Ancient Greece topic. Following them would turn a
    // school-information crawler into a general web spider, and put third-party pages into a
    // corpus that answers in the school's voice.
    assertThat(filter.isCrawlableWebsitePage(
        "http://www.primaryhomeworkhelp.co.uk/Greece.html")).isFalse();
    assertThat(filter.isCrawlableWebsitePage(
        "https://www.natgeokids.com/uk/discover/history/greece/")).isFalse();
    assertThat(filter.isCrawlableWebsitePage(
        "https://kids.britannica.com/kids/article/ancient-Greece/353213")).isFalse();
    // The suffix test is anchored on a dot, so a lookalike host does not pass.
    assertThat(filter.isCrawlableWebsitePage("https://notkilmorieschool.co.uk/year-six"))
        .isFalse();
  }

  @Test
  @DisplayName("the calendar is not crawled as a page")
  void calendarIsNotCrawlable() {
    // Its date-parameterised URL space is effectively infinite — the same reason the sitemap
    // crawl drops it.
    assertThat(filter.isCrawlableWebsitePage(
        "https://www.kilmorieschool.co.uk/calendar/?calid=2,2&pid=142")).isFalse();
  }

  @Test
  @DisplayName("a PDF is not queued as a page")
  void pdfsAreNotCrawlableAsPages() {
    // They are ingested, but by SchoolIngestService's PDF path. Putting one on the page queue
    // would try to read a binary as HTML.
    assertThat(filter.isCrawlableWebsitePage(
        "https://www.kilmorieschool.co.uk/_site/data/files/938A752E.pdf")).isFalse();
  }

  @Test
  @DisplayName("cookie, privacy and accessibility pages are not crawled")
  void boilerplatePagesAreNotCrawled() {
    // Shared with isWorthOffering rather than listed twice, so the two cannot drift.
    assertThat(filter.isCrawlableWebsitePage(
        "https://www.kilmorieschool.co.uk/privacy-cookies")).isFalse();
    assertThat(filter.isCrawlableWebsitePage(
        "https://www.kilmorieschool.co.uk/accessibility-statement/")).isFalse();
    assertThat(filter.isCrawlableWebsitePage(
        "https://www.kilmorieschool.co.uk/accessibility.asp?level=high-vis&item=page_96"))
        .isFalse();
    // And the bare homepage, which every page links back to.
    assertThat(filter.isCrawlableWebsitePage("https://www.kilmorieschool.co.uk/")).isFalse();
  }

  @Test
  @DisplayName("a non-http scheme is never crawled")
  void nonHttpSchemesAreNotCrawled() {
    assertThat(filter.isCrawlableWebsitePage("mailto:office@kilmorieschool.co.uk")).isFalse();
    assertThat(filter.isCrawlableWebsitePage("javascript:alert(1)")).isFalse();
    assertThat(filter.isCrawlableWebsitePage("file:///etc/passwd")).isFalse();
    assertThat(filter.isCrawlableWebsitePage(null)).isFalse();
    assertThat(filter.isCrawlableWebsitePage("   ")).isFalse();
  }
}
