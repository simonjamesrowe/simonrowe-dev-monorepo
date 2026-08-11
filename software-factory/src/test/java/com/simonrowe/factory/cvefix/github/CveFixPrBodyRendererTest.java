package com.simonrowe.factory.cvefix.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import com.simonrowe.factory.cvefix.workflow.CveFixActivities.FixSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

class CveFixPrBodyRendererTest {

  private static final FixSummary SUMMARY =
      new FixSummary(
          List.of("jackson-databind 2.15.0 -> 2.17.1 (CVE-2024-1111)"),
          List.of(
              new UnfixableComponent(
                  "pkg:npm/left-pad@1.0.0", List.of("CVE-2024-2222"),
                  "no released version clears the advisory")),
          "Bumped one component and left one component unfixable.");

  @Test
  void titleIsFixedProseRegardlessOfSummary() {
    assertThat(CveFixPrBodyRenderer.title(SUMMARY))
        .isEqualTo("chore: bump dependencies with Dependency-Track findings");
  }

  @Test
  void bodyListsBumpsThenUnfixableThenTheAgentSummary() {
    String body = CveFixPrBodyRenderer.body(SUMMARY);

    assertThat(body).contains("jackson-databind 2.15.0 -> 2.17.1 (CVE-2024-1111)");
    assertThat(body).contains("pkg:npm/left-pad@1.0.0: no released version clears the advisory");
    assertThat(body).contains("Bumped one component and left one component unfixable.");
    int bumpIndex = body.indexOf("jackson-databind");
    int unfixableIndex = body.indexOf("left-pad");
    int summaryIndex = body.indexOf("Bumped one component");
    assertThat(bumpIndex).isLessThan(unfixableIndex);
    assertThat(unfixableIndex).isLessThan(summaryIndex);
  }

  @Test
  void bodyShowsNoneForEmptyBumpsAndUnfixable() {
    FixSummary empty = new FixSummary(List.of(), List.of(), "Nothing to report.");

    String body = CveFixPrBodyRenderer.body(empty);

    assertThat(body).contains("## Dependency bumps\n- none");
    assertThat(body).contains("## Left unfixable\n- none");
    assertThat(body).contains("Nothing to report.");
  }

  @Test
  void giveUpCommentIncludesTheAttemptCountAndTheSameContentAsTheBody() {
    String comment = CveFixPrBodyRenderer.giveUpComment(SUMMARY, 3);

    assertThat(comment).contains("3 repair attempt(s)");
    assertThat(comment).contains("jackson-databind 2.15.0 -> 2.17.1 (CVE-2024-1111)");
    assertThat(comment).contains("pkg:npm/left-pad@1.0.0: no released version clears the advisory");
    assertThat(comment).contains("Bumped one component and left one component unfixable.");
  }
}
