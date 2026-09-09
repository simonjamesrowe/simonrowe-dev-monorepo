package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GmailMessageTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private static String base64Url(final String value) {
    return java.util.Base64.getUrlEncoder()
        .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private GmailMessage parse(final String json) {
    return GmailMessage.from(mapper.readTree(json));
  }

  @Test
  @DisplayName("the sender ADDRESS is extracted, not the display name")
  void extractsTheAddressNotTheDisplayName() {
    // The real case from this mailbox: a third-party system sending under the school's name.
    // Anything that allowlists on the display name admits it.
    final GmailMessage message = parse("""
        {"id":"1","internalDate":"1757000000000","payload":{"headers":[
          {"name":"From","value":"\\"Kilmorie Primary School\\" <system@insighttracking.com>"},
          {"name":"Subject","value":"Reports"}],
          "mimeType":"text/plain","body":{"data":"SGVsbG8="}}}
        """);

    assertThat(message.fromAddress()).isEqualTo("system@insighttracking.com");
    assertThat(message.fromDisplayName()).isEqualTo("Kilmorie Primary School");
  }

  @Test
  @DisplayName("a bare address with no angle brackets still parses")
  void bareAddressParses() {
    final GmailMessage message = parse("""
        {"id":"2","internalDate":"0","payload":{"headers":[
          {"name":"From","value":"info@kilmorie.lewisham.sch.uk"}],
          "mimeType":"text/plain","body":{"data":"SGk="}}}
        """);

    assertThat(message.fromAddress()).isEqualTo("info@kilmorie.lewisham.sch.uk");
  }

  @Test
  @DisplayName("an attachment is identified by attachmentId, never by size")
  void attachmentsIdentifiedByAttachmentId() {
    // Small attachments are inlined and large ones are not, with no documented boundary, so a
    // size heuristic silently misclassifies one or the other.
    final GmailMessage message = parse("""
        {"id":"3","internalDate":"0","payload":{"headers":[],"mimeType":"multipart/mixed",
          "parts":[
            {"mimeType":"text/plain","filename":"","body":{"data":"Tm90ZQ=="}},
            {"mimeType":"application/pdf","filename":"newsletter.pdf",
             "body":{"attachmentId":"abc","size":12}}]}}
        """);

    assertThat(message.attachments()).singleElement()
        .satisfies(a -> {
          assertThat(a.filename()).isEqualTo("newsletter.pdf");
          // The id is what makes the attachment downloadable. Recording only the filename —
          // which this did originally — meant the newsletter PDF was noted and discarded.
          assertThat(a.attachmentId()).isEqualTo("abc");
          assertThat(a.looksLikePdf()).isTrue();
        });
    assertThat(message.body()).isEqualTo("Note");
  }

  @Test
  @DisplayName("a PDF is recognised from the filename when the sender mislabels the type")
  void pdfRecognisedFromFilename() {
    // Senders mislabel attachments routinely; application/octet-stream on a .pdf is common.
    final GmailMessage message = parse("""
        {"id":"5","internalDate":"0","payload":{"headers":[],"mimeType":"multipart/mixed",
          "parts":[{"mimeType":"application/octet-stream","filename":"Autumn-timetable.pdf",
             "body":{"attachmentId":"xyz","size":900}}]}}
        """);

    assertThat(message.attachments()).singleElement()
        .satisfies(a -> assertThat(a.looksLikePdf()).isTrue());
  }

  @Test
  @DisplayName("HTML is used only when there is no plain part, so text is not duplicated")
  void htmlOnlyWhenNoPlainPart() {
    final GmailMessage both = parse("""
        {"id":"4","internalDate":"0","payload":{"headers":[],"mimeType":"multipart/alternative",
          "parts":[
            {"mimeType":"text/plain","filename":"","body":{"data":"UGxhaW4gdGV4dA=="}},
            {"mimeType":"text/html","filename":"","body":{"data":"PHA-UGxhaW4gdGV4dDwvcD4="}}]}}
        """);

    assertThat(both.body()).isEqualTo("Plain text");
  }

  @Test
  @DisplayName("links are collected from the HTML part even when a plain part supplied the text")
  void linksCollectedAlongsidePlainText() {
    // multipart/alternative with the plain part first is the ordinary case. Gating the HTML
    // branch on "no text yet" — which it originally was — meant no link was ever recorded for
    // a normal school newsletter.
    final String html = base64Url(
        "<a href=\"https://example.org/menu.pdf\">Autumn menu</a>");
    final GmailMessage message = parse("""
        {"id":"6","internalDate":"0","payload":{"headers":[],
          "mimeType":"multipart/alternative","parts":[
            {"mimeType":"text/plain","filename":"",
             "body":{"data":"UGxhaW4gdGV4dA=="}},
            {"mimeType":"text/html","filename":"","body":{"data":"%s"}}]}}
        """.formatted(html));

    assertThat(message.body()).isEqualTo("Plain text");
    assertThat(message.links()).singleElement().satisfies(link -> {
      assertThat(link.url()).isEqualTo("https://example.org/menu.pdf");
      assertThat(link.text()).isEqualTo("Autumn menu");
    });
  }

  @Test
  @DisplayName("base64url payloads containing - and _ decode correctly")
  void decodesBase64Url() {
    // "<p>Plain text</p>" encodes to a string containing '-' in base64url. The standard
    // decoder throws on it, and the symptom would be a silently empty message body.
    assertThat(GmailClient.decode("PHA-UGxhaW4gdGV4dDwvcD4=")).isEqualTo("<p>Plain text</p>");
  }

  @Test
  @DisplayName("undecodable data yields empty rather than throwing mid-sync")
  void badDataIsEmpty() {
    assertThat(GmailClient.decode("!!!not base64!!!")).isEmpty();
    assertThat(GmailClient.decode(null)).isEmpty();
  }
}
