package com.simonrowe.resume;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResumePdfRendererTest {

  private final ResumePdfRenderer renderer = new ResumePdfRenderer();

  @Test
  void renderProducesNonEmptyPdfBytes() {
    ResumeData data = sampleResumeData();

    byte[] pdf = renderer.render(data);

    assertThat(pdf).isNotEmpty();
    assertThat(pdf[0]).isEqualTo((byte) '%');
    assertThat(pdf[1]).isEqualTo((byte) 'P');
    assertThat(pdf[2]).isEqualTo((byte) 'D');
    assertThat(pdf[3]).isEqualTo((byte) 'F');
  }

  @Test
  void renderHandlesEmptyCollections() {
    ResumeData data = new ResumeData(
        new ResumeProfile("Name", "Title", "Headline", "email", "phone",
            "London", null, null, null),
        List.of(), List.of(), List.of());

    byte[] pdf = renderer.render(data);

    assertThat(pdf).isNotEmpty();
  }

  @Test
  void renderHandlesMarkdownInDescriptions() {
    ResumeJob job = new ResumeJob(
        "Lead", "Company", "2020-01-01", null, "London",
        "Compact summary",
        "**Bold** text and _italic_ with `code`");
    ResumeData data = new ResumeData(
        new ResumeProfile("Name", "Title", "Headline", "email", "phone",
            "London", null, null, null),
        List.of(job), List.of(), List.of());

    byte[] pdf = renderer.render(data);

    assertThat(pdf).isNotEmpty();
  }

  @Test
  void renderUsesCondensedFormatAfterFifthEmploymentRole() throws IOException {
    List<ResumeJob> employment = List.of(
        sampleEmployment("Role 1", "2019-01-01", "Detailed role 1", "Full detail 1"),
        sampleEmployment("Role 2", "2018-01-01", "Detailed role 2", "Full detail 2"),
        sampleEmployment("Role 3", "2017-01-01", "Detailed role 3", "Full detail 3"),
        sampleEmployment("Role 4", "2016-01-01", "Detailed role 4", "Full detail 4"),
        sampleEmployment("Role 5", "2015-01-01", "Detailed role 5", "Full detail 5"),
        sampleEmployment(
            "Role 6", "2014-01-01", "Compact role summary",
            "Older full detail should not be used"),
        sampleEmployment(
            "Role 7", "2013-01-01", "Another compact role summary",
            "Another older full detail")
    );
    ResumeData data = new ResumeData(
        new ResumeProfile("Name", "Title", "Headline", "email", "phone",
            "London", null, null, null),
        employment, List.of(), List.of());

    byte[] pdf = renderer.render(data);
    String extractedText = extractText(pdf);

    assertThat(extractedText).contains("Role 6");
    assertThat(extractedText).contains("Company");
    assertThat(extractedText).contains("London");
    assertThat(extractedText).contains("Compact role summary");
    assertThat(extractedText).doesNotContain("Older full detail should not be used");
  }

  private static ResumeData sampleResumeData() {
    ResumeProfile profile = new ResumeProfile(
        "Simon Rowe", "Engineering Leader",
        "Passionate about building cloud native apps",
        "simon@test.com", "+44123456", "London",
        "https://linkedin.com/in/simon",
        "https://github.com/simon",
        "https://simonrowe.dev");

    ResumeJob employment = new ResumeJob(
        "Lead Engineer", "Upp Technologies",
        "2019-04-15", "2020-05-01", "London",
        "Lead engineer across multiple product verticals.",
        "Lead engineer working on all verticals.");

    ResumeJob education = new ResumeJob(
        "BSc Computer Science", "University of Leeds",
        "2008-09-01", "2011-06-01", "Leeds",
        "Computer science degree.",
        "First class honours degree.");

    ResumeSkillGroup group = new ResumeSkillGroup("Spring", 9.5);

    return new ResumeData(profile, List.of(employment),
        List.of(education), List.of(group));
  }

  private static ResumeJob sampleEmployment(
      String title, String startDate, String shortDescription, String longDescription
  ) {
    return new ResumeJob(
        title, "Company", startDate, null, "London", shortDescription, longDescription);
  }

  private static String extractText(byte[] pdf) throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      StringBuilder text = new StringBuilder();
      for (int page = 1; page <= reader.getNumberOfPages(); page++) {
        text.append(extractor.getTextFromPage(page));
      }
      return text.toString();
    } finally {
      reader.close();
    }
  }
}
