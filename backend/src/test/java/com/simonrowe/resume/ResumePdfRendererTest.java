package com.simonrowe.resume;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResumePdfRendererTest {

  private final ResumePdfRenderer renderer = new ResumePdfRenderer(
      new ResumeMarkdownParser(), new ResumePhotoProvider("build/no-uploads-here"));

  @Test
  void renderProducesNonEmptyPdfBytes() {
    byte[] pdf = renderer.render(sampleResumeData());

    assertThat(pdf).isNotEmpty();
    assertThat(pdf[0]).isEqualTo((byte) '%');
    assertThat(pdf[1]).isEqualTo((byte) 'P');
    assertThat(pdf[2]).isEqualTo((byte) 'D');
    assertThat(pdf[3]).isEqualTo((byte) 'F');
  }

  @Test
  void renderHandlesEmptyCollections() {
    ResumeData data = new ResumeData(
        profile(null), List.of(), List.of(), List.of());

    byte[] pdf = renderer.render(data);

    assertThat(pdf).isNotEmpty();
  }

  @Test
  void renderKeepsMarkdownHeadingsAndBulletText() throws IOException {
    ResumeJob job = new ResumeJob(
        "Lead", "Company", "2020-01-01", null, "London", "Compact summary",
        """
        ## Team Leadership
        - Grew the team with senior hires
        - Coached tech leads across each pillar
        """);
    ResumeData data = new ResumeData(
        profile(null), List.of(job), List.of(), List.of());

    String text = extractText(renderer.render(data));

    assertThat(text).contains("Team Leadership");
    assertThat(text).contains("Grew the team with senior hires");
    // The literal asterisks the old plain-text renderer emitted for every bullet.
    assertThat(text).doesNotContain("* Grew the team");
  }

  @Test
  void renderHandlesInlineMarkdownEmphasis() throws IOException {
    ResumeJob job = new ResumeJob(
        "Lead", "Company", "2020-01-01", null, "London", "Compact summary",
        "**Bold** text and _italic_ with `code`");
    ResumeData data = new ResumeData(
        profile(null), List.of(job), List.of(), List.of());

    String text = extractText(renderer.render(data));

    assertThat(text).contains("Bold text and italic with code");
  }

  @Test
  void renderUsesTheCompactFormatForOlderShorterRoles() throws IOException {
    List<ResumeJob> employment = new ArrayList<>(List.of(
        detailedRole("Role 1", "2022-01-01", "2023-01-01"),
        detailedRole("Role 2", "2021-01-01", "2022-01-01"),
        detailedRole("Role 3", "2020-01-01", "2021-01-01"),
        detailedRole("Role 4", "2019-01-01", "2020-01-01")));
    employment.add(new ResumeJob(
        "Role 5", "Company", "2018-01-01", "2019-01-01", "London",
        "Compact role summary", "Older full detail should not be used"));

    ResumeData data = new ResumeData(
        profile(null), employment, List.of(), List.of());

    String text = extractText(renderer.render(data));

    assertThat(text).contains("Role 5");
    assertThat(text).contains("Compact role summary");
    assertThat(text).doesNotContain("Older full detail should not be used");
  }

  @Test
  void renderKeepsTheFullDescriptionOfAnOlderButLongTenuredRole() throws IOException {
    List<ResumeJob> employment = new ArrayList<>(List.of(
        detailedRole("Role 1", "2022-01-01", "2023-01-01"),
        detailedRole("Role 2", "2021-01-01", "2022-01-01"),
        detailedRole("Role 3", "2020-01-01", "2021-01-01"),
        detailedRole("Role 4", "2019-01-01", "2020-01-01")));
    employment.add(new ResumeJob(
        "Long Tenure", "Company", "2011-01-01", "2018-01-01", "London",
        "Compact role summary", "Seven years of detail that must survive"));

    ResumeData data = new ResumeData(
        profile(null), employment, List.of(), List.of());

    String text = extractText(renderer.render(data));

    assertThat(text).contains("Seven years of detail that must survive");
  }

  @Test
  void renderCapsBulletsPerSubHeadingSoEveryThemeSurvives() throws IOException {
    ResumeJob job = new ResumeJob(
        "Lead", "Company", "2020-01-01", null, "London", "Compact summary",
        """
        ## First Theme
        - First kept bullet
        - Second kept bullet
        - Third dropped bullet

        ## Last Theme
        - Last theme bullet
        """);
    ResumeData data = new ResumeData(
        profile(null), List.of(job), List.of(), List.of());

    String text = extractText(renderer.render(data));

    assertThat(text).contains("First kept bullet");
    assertThat(text).contains("Second kept bullet");
    assertThat(text).doesNotContain("Third dropped bullet");
    // The point of a per-heading cap: a later theme is not starved by an earlier one.
    assertThat(text).contains("Last Theme");
    assertThat(text).contains("Last theme bullet");
  }

  @Test
  void renderDropsSubHeadingsLeftWithNoBullets() throws IOException {
    StringBuilder markdown = new StringBuilder();
    for (int i = 1; i <= 12; i++) {
      markdown.append("## Theme ").append(i).append('\n');
      markdown.append("- Bullet ").append(i).append("a\n");
      markdown.append("- Bullet ").append(i).append("b\n\n");
    }
    ResumeJob job = new ResumeJob(
        "Lead", "Company", "2020-01-01", null, "London", "Compact summary",
        markdown.toString());
    ResumeData data = new ResumeData(
        profile(null), List.of(job), List.of(), List.of());

    String text = extractText(renderer.render(data));

    // 12 themes x 2 bullets exceeds the per-role budget, so the last themes are cut —
    // heading and all, never a heading standing over nothing.
    assertThat(text).contains("Theme 8");
    assertThat(text).contains("Bullet 8b");
    assertThat(text).doesNotContain("Theme 9");
    assertThat(text).doesNotContain("Bullet 9a");
  }

  @Test
  void renderPutsEducationInTheSidebar() throws IOException {
    ResumeJob course = new ResumeJob(
        "Computer Science", "University of Newcastle", "2002-01-01", "2004-12-02",
        "Newcastle", "Bachelor of Computer Science", null);
    ResumeData data = new ResumeData(
        profile(null), List.of(), List.of(course), List.of());

    String text = extractText(renderer.render(data));

    assertThat(text).contains("EDUCATION");
    assertThat(text).contains("Computer Science");
    assertThat(text).contains("University of Newcastle");
  }

  @Test
  void renderUsesTheCmsSummaryWhenThereIsOne() throws IOException {
    ResumeData data = new ResumeData(
        profile("Engineering leader with twenty years of experience."),
        List.of(), List.of(), List.of());

    String text = extractText(renderer.render(data));

    assertThat(text).contains("Engineering leader with twenty years of experience.");
    // The site headline is a tagline, and duplicating it under the summary reads badly.
    assertThat(text).doesNotContain("PASSIONATE ABOUT AI-NATIVE DEVELOPMENT");
  }

  @Test
  void renderFallsBackToTheHeadlineAndRecasesIt() throws IOException {
    ResumeData data = new ResumeData(
        profile(null), List.of(), List.of(), List.of());

    String text = extractText(renderer.render(data));

    assertThat(text).contains("Passionate about ai-native development");
    assertThat(text).doesNotContain("PASSIONATE ABOUT AI-NATIVE DEVELOPMENT");
  }

  @Test
  void renderClosesTheFinalPageWithPointerToTheSite() throws IOException {
    ResumeData data = new ResumeData(
        profile("A summary."), realisticHistory(), List.of(education()), skillGroups());

    List<String> pages = extractPages(renderer.render(data));

    assertThat(pages).hasSizeGreaterThan(1);
    assertThat(pages.get(pages.size() - 1)).contains("See simonrowe.dev for more.");
  }

  @Test
  void renderPutsTheClosingLineOnTheLastPageOnly() throws IOException {
    ResumeData data = new ResumeData(
        profile("A summary."), realisticHistory(), List.of(education()), skillGroups());

    List<String> pages = extractPages(renderer.render(data));

    assertThat(pages.subList(0, pages.size() - 1))
        .noneMatch(page -> page.contains("for more."));
  }

  @Test
  void renderOmitsTheClosingLineWhenThereIsNoWebsite() throws IOException {
    ResumeProfile withoutWebsite = new ResumeProfile(
        "Simon Rowe", "Software Engineering Leader", "A headline",
        "simon@test.com", "+44123456", "London", null, null, null, "A summary.", null);
    ResumeData data = new ResumeData(
        withoutWebsite, List.of(), List.of(), List.of());

    assertThat(extractText(renderer.render(data))).doesNotContain("for more.");
  }

  @Test
  void renderShortensLinksSoTheyFitTheSidebar() throws IOException {
    ResumeData data = new ResumeData(
        profile(null), List.of(), List.of(), List.of());

    String text = extractText(renderer.render(data));

    assertThat(text).contains("linkedin.com/in/simon");
    assertThat(text).doesNotContain("https://www.linkedin.com");
  }

  @Test
  void renderKeepsRealisticTwentyYearHistoryWithinThreePages() throws IOException {
    ResumeData data = new ResumeData(
        profile("A summary of roughly the length the CMS field allows, which is about "
            + "seventy words and runs to five or six lines in the main column of the "
            + "rendered document before the experience section begins below it."),
        realisticHistory(), List.of(education()), skillGroups());

    assertThat(pageCount(renderer.render(data))).isLessThanOrEqualTo(3);
  }

  @Test
  void renderBoundsLongDescriptionsRatherThanGrowingWithThem() throws IOException {
    // The guarantee the taper actually gives: past the cap, more content costs no more
    // pages. A page-count assertion on one fixed fixture would only ever pin the
    // fixture; this pins the mechanism.
    ResumeData modest = new ResumeData(
        profile("A summary."), List.of(roleWithThemes("Lead", "2020-01-01", 9, 4)),
        List.of(), List.of());
    ResumeData enormous = new ResumeData(
        profile("A summary."), List.of(roleWithThemes("Lead", "2020-01-01", 40, 20)),
        List.of(), List.of());

    assertThat(pageCount(renderer.render(enormous)))
        .isEqualTo(pageCount(renderer.render(modest)));
  }

  /** Seven roles shaped like the real CMS data: one heavy current role, then prose. */
  private static List<ResumeJob> realisticHistory() {
    List<ResumeJob> employment = new ArrayList<>();
    employment.add(roleWithThemes("Head of Engineering", "2021-08-01", 9, 4));
    employment.add(roleWithProse("Senior Developer", "2020-05-04", "2021-07-30", 3, 7));
    employment.add(roleWithProse("Engineering Lead", "2019-04-15", "2020-05-01", 2, 7));
    employment.add(roleWithProse("Platform Architect", "2018-08-01", "2019-04-12", 2, 0));
    employment.add(roleWithProse("Senior Director", "2011-07-12", "2018-08-11", 3, 0));
    employment.add(roleWithProse("Senior Developer", "2009-11-01", "2011-05-19", 3, 0));
    employment.add(roleWithProse("Analyst", "2008-02-01", "2009-11-01", 3, 0));
    return employment;
  }

  private static ResumeJob roleWithThemes(
      String title, String startDate, int themes, int bulletsPerTheme
  ) {
    StringBuilder markdown = new StringBuilder();
    for (int theme = 1; theme <= themes; theme++) {
      markdown.append("## Theme ").append(theme).append('\n');
      for (int bullet = 1; bullet <= bulletsPerTheme; bullet++) {
        markdown.append("- ").append(sentence(bullet)).append('\n');
      }
      markdown.append('\n');
    }
    return new ResumeJob(title, "Company", startDate, null, "London",
        "A compact one-line summary of the role for the reader.", markdown.toString());
  }

  private static ResumeJob roleWithProse(
      String title, String startDate, String endDate, int paragraphs, int bullets
  ) {
    StringBuilder markdown = new StringBuilder();
    for (int paragraph = 1; paragraph <= paragraphs; paragraph++) {
      markdown.append(sentence(paragraph)).append(' ').append(sentence(paragraph + 1))
          .append("\n\n");
    }
    for (int bullet = 1; bullet <= bullets; bullet++) {
      markdown.append("- ").append(sentence(bullet)).append('\n');
    }
    return new ResumeJob(title, "Company", startDate, endDate, "London",
        "A compact one-line summary of the role for the reader.", markdown.toString());
  }

  private static String sentence(int seed) {
    return "A realistically long line describing responsibility " + seed
        + " in the sort of detail a reader of this document would expect to find.";
  }

  private static int pageCount(byte[] pdf) throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      return reader.getNumberOfPages();
    } finally {
      reader.close();
    }
  }

  private static ResumeProfile profile(String summary) {
    return new ResumeProfile(
        "Simon Rowe", "Software Engineering Leader",
        "PASSIONATE ABOUT AI-NATIVE DEVELOPMENT",
        "simon@test.com", "+44123456", "London",
        "https://www.linkedin.com/in/simon",
        "https://github.com/simon",
        "https://simonrowe.dev",
        summary,
        null);
  }

  private static ResumeData sampleResumeData() {
    ResumeJob employment = new ResumeJob(
        "Lead Engineer", "Upp Technologies", "2019-04-15", "2020-05-01", "London",
        "Lead engineer across multiple product verticals.",
        "Lead engineer working on all verticals.");

    return new ResumeData(
        profile("A summary."), List.of(employment), List.of(education()),
        skillGroups());
  }

  private static ResumeJob education() {
    return new ResumeJob(
        "BSc Computer Science", "University of Leeds", "2008-09-01", "2011-06-01",
        "Leeds", "Computer science degree.", "First class honours degree.");
  }

  private static List<ResumeSkillGroup> skillGroups() {
    return List.of(
        new ResumeSkillGroup("Spring"), new ResumeSkillGroup("Java / Kotlin"));
  }

  private static ResumeJob detailedRole(
      String title, String startDate, String endDate
  ) {
    return new ResumeJob(
        title, "Company", startDate, endDate, "London",
        "Compact summary for " + title, "Full detail for " + title);
  }

  private static List<String> extractPages(byte[] pdf) throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      List<String> pages = new ArrayList<>();
      for (int page = 1; page <= reader.getNumberOfPages(); page++) {
        pages.add(extractor.getTextFromPage(page));
      }
      return pages;
    } finally {
      reader.close();
    }
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
