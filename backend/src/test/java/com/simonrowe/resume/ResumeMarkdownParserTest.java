package com.simonrowe.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResumeMarkdownParserTest {

  private final ResumeMarkdownParser parser = new ResumeMarkdownParser();

  @Test
  void parsesHeadingsBulletsAndProseAsDistinctBlocks() {
    List<ResumeBlock> blocks = parser.parse("""
        ## Team Leadership

        Ran three squads across the department.

        - Recruited and onboarded engineers
        - Coached tech leads
        """);

    assertThat(blocks).hasSize(4);
    assertThat(blocks.get(0).kind()).isEqualTo(ResumeBlock.Kind.HEADING);
    assertThat(blocks.get(0).plainText()).isEqualTo("Team Leadership");
    assertThat(blocks.get(1).kind()).isEqualTo(ResumeBlock.Kind.PARAGRAPH);
    assertThat(blocks.get(2).kind()).isEqualTo(ResumeBlock.Kind.BULLET);
    assertThat(blocks.get(2).plainText()).isEqualTo("Recruited and onboarded engineers");
    assertThat(blocks.get(3).kind()).isEqualTo(ResumeBlock.Kind.BULLET);
  }

  @Test
  void keepsEmphasisAsStyledRuns() {
    List<ResumeBlock> blocks = parser.parse("A **bold** and _italic_ line.");

    List<ResumeTextRun> runs = blocks.get(0).runs();
    assertThat(runs).extracting(ResumeTextRun::text)
        .containsExactly("A ", "bold", " and ", "italic", " line.");
    assertThat(runs.get(1).bold()).isTrue();
    assertThat(runs.get(3).italic()).isTrue();
    assertThat(blocks.get(0).plainText()).isEqualTo("A bold and italic line.");
  }

  @Test
  void mergesAdjacentRunsSharingTheSameStyle() {
    List<ResumeBlock> blocks = parser.parse("Plain text with `code` inside it.");

    // Inline code is not a distinct style on a CV, so it must not fragment the run.
    assertThat(blocks.get(0).runs()).hasSize(1);
  }

  @Test
  void rendersLinksAsTheirTextNotTheirUrl() {
    List<ResumeBlock> blocks = parser.parse("See [the platform](https://example.com).");

    assertThat(blocks.get(0).plainText()).isEqualTo("See the platform.");
    assertThat(blocks.get(0).plainText()).doesNotContain("example.com");
  }

  @Test
  void treatsOrderedListsAsBullets() {
    List<ResumeBlock> blocks = parser.parse("""
        1. First
        2. Second
        """);

    assertThat(blocks).allMatch(block -> block.kind() == ResumeBlock.Kind.BULLET);
    assertThat(blocks).hasSize(2);
  }

  @Test
  void flattensNestedListsIntoTheEnclosingBullet() {
    List<ResumeBlock> blocks = parser.parse("""
        - Outer point
          - Inner detail
        """);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).plainText()).isEqualTo("Outer point Inner detail");
  }

  @Test
  void joinsSoftLineBreaksWithSpaceRatherThanRunningWordsTogether() {
    List<ResumeBlock> blocks = parser.parse("First line\nsecond line");

    assertThat(blocks.get(0).plainText()).isEqualTo("First line second line");
  }

  @Test
  void returnsNothingForNullOrBlankInput() {
    assertThat(parser.parse(null)).isEmpty();
    assertThat(parser.parse("   ")).isEmpty();
  }
}
