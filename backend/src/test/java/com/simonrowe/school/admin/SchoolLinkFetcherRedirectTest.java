package com.simonrowe.school.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.ingest.DocumentDateReader;
import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.ingest.SchoolDocumentWriter;
import com.simonrowe.school.ingest.SchoolEventWriter;
import com.simonrowe.school.ingest.SchoolIngestService;
import com.simonrowe.school.ingest.SchoolLinkFilter;
import com.simonrowe.school.ingest.SchoolPdfExtractor;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.webfetch.UrlFetcher;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the SSRF posture of the on-demand link fetcher.
 *
 * <p>The URLs this class fetches come out of email a hostile sender controls. It originally
 * validated the first URL and then let the JDK client follow redirects itself, which guards
 * exactly one hop: a link that passes the public-address check can answer 302 and point at
 * {@code http://169.254.169.254/}, and the client follows it without asking anything.
 */
class SchoolLinkFetcherRedirectTest {

  private static SchoolLinkFetcher newFetcher() {
    return new SchoolLinkFetcher(
        mock(SchoolLinkRepository.class),
        mock(SchoolDocumentRepository.class),
        mock(SchoolDocumentWriter.class),
        mock(SchoolPdfExtractor.class),
        mock(SchoolAttachmentStore.class),
        mock(SchoolIngestService.class),
        mock(SchoolEventExtractor.class),
        mock(SchoolEventWriter.class),
        mock(DocumentDateReader.class),
        mock(SchoolLinkFilter.class));
  }

  @Test
  @DisplayName("the client never follows a redirect on its own")
  void clientNeverAutoFollowsRedirects() throws Exception {
    // Configuration rather than behaviour, deliberately: an end-to-end test cannot easily reach
    // this path, because the FIRST url must pass isFetchableUrl and a loopback test server
    // never will. This pins the one setting whose change reintroduces the whole hole.
    final Field field = SchoolLinkFetcher.class.getDeclaredField("httpClient");
    field.setAccessible(true);
    final HttpClient client = (HttpClient) field.get(newFetcher());

    assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
  }

  @Test
  @DisplayName("the addresses a redirect would be abused to reach are refused by the guard")
  void guardRefusesInternalAddresses() {
    // The manual hop loop is only as good as the check it calls on each hop.
    assertThat(UrlFetcher.isFetchableUrl("http://169.254.169.254/latest/meta-data/")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://127.0.0.1:8080/actuator/env")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://localhost/")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://10.0.0.1/")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("file:///etc/passwd")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("https://www.kilmorieschool.co.uk/")).isTrue();
  }
}
