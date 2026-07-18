package com.simonrowe.webfetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlFetcherTest {

  @Test
  void rejectsNonHttpSchemes() {
    assertThat(UrlFetcher.isFetchableUrl("file:///etc/passwd")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("ftp://example.com/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("gopher://example.com")).isFalse();
  }

  @Test
  void rejectsBlankOrMalformedUrls() {
    assertThat(UrlFetcher.isFetchableUrl(null)).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("   ")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("not a url")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://")).isFalse();
  }

  @Test
  void rejectsLoopbackAndInternalHosts() {
    assertThat(UrlFetcher.isFetchableUrl("http://localhost:8080/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://127.0.0.1/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://[::1]/x")).isFalse();
    // These internal container names have no public DNS, so they exercise the
    // UnknownHostException branch (unresolvable host) in this environment.
    assertThat(UrlFetcher.isFetchableUrl("http://searxng:8080/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://portainer:9000/x")).isFalse();
  }

  @Test
  void rejectsPrivateAndMetadataAddresses() {
    assertThat(UrlFetcher.isFetchableUrl("http://10.0.0.5/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://192.168.1.10/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://172.16.4.4/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://169.254.169.254/latest/meta-data")).isFalse();
  }

  @Test
  void acceptsPublicHttpsUrls() {
    assertThat(UrlFetcher.isFetchableUrl("https://boards.greenhouse.io/acme/jobs/123")).isTrue();
    assertThat(UrlFetcher.isFetchableUrl("https://www.reed.co.uk/jobs/head-of-engineering/999")).isTrue();
  }

  @Test
  void extractsTitleAndTruncatesText() {
    final org.jsoup.nodes.Document doc =
        org.jsoup.Jsoup.parse("<html><head><title>Head of Engineering</title></head>"
            + "<body><h1>Role</h1><p>abcdefghijklmnop</p></body></html>");

    final WebPageContent content = UrlFetcher.extract(doc, "https://example.com", 10);

    assertThat(content.title()).isEqualTo("Head of Engineering");
    assertThat(content.url()).isEqualTo("https://example.com");
    assertThat(content.text()).hasSize(10);
  }
}
