package com.simonrowe.factory.codereview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds {@link MergeDisposition} to exactly the answers {@code scripts/classify-change.sh} gives.
 *
 * <p>The cases are not written here. They are read from the fixture the bash test also reads, so
 * the factory cannot come to arm auto-merge on a change the script would call manual, or the
 * reverse, without one of the two suites failing.
 */
class MergeDispositionTest {

  /** The Gradle test working directory is {@code software-factory/}, one below the repo root. */
  private static final Path FIXTURE =
      Path.of("..", "scripts", "test", "fixtures", "merge-disposition-cases.tsv");

  static Stream<Arguments> sharedCases() throws IOException {
    List<Arguments> cases = new ArrayList<>();
    for (String line : Files.readAllLines(FIXTURE, StandardCharsets.UTF_8)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] columns = line.split("\t", -1);
      List<String> paths =
          columns.length > 3 && !columns[3].isBlank()
              ? Arrays.asList(columns[3].trim().split(" +"))
              : List.of();
      cases.add(Arguments.of(columns[0], columns[1], paths));
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sharedCases")
  void answersExactlyAsTheScriptDoes(
      final String description, final String category, final List<String> paths) {
    assertThat(MergeDisposition.classify(paths).disposition())
        .as(description)
        .isEqualTo(fromScriptCategory(category));
  }

  @Test
  void theFixtureIsReadAndCoversEveryDisposition() throws IOException {
    // A wrong path or a format change must not turn the parameterised test above into zero
    // cases, which JUnit reports as a pass.
    List<Arguments> cases = sharedCases().toList();
    assertThat(cases).hasSizeGreaterThan(20);
    assertThat(cases.stream().map(arguments -> (String) arguments.get()[1]).distinct())
        .containsExactlyInAnyOrder("auto-merge", "ux-review", "manual");
  }

  @Test
  void namesThePathThatForcedHumanReview() {
    assertThat(
            MergeDisposition.classify(List.of("backend/A.java", "docker-compose.prod.yml")))
        .isEqualTo(
            new MergeDisposition.Classification(
                MergeDisposition.MANUAL, "docker-compose.prod.yml"));
    assertThat(MergeDisposition.classify(List.of("frontend/src/App.tsx", "backend/A.java")))
        .isEqualTo(
            new MergeDisposition.Classification(
                MergeDisposition.UX_REVIEW, "frontend/src/App.tsx"));
    assertThat(MergeDisposition.classify(List.of("backend/A.java")))
        .isEqualTo(new MergeDisposition.Classification(MergeDisposition.AUTO_MERGE, null));
  }

  @Test
  void globStarCrossesSlashesLikeShellCasePattern() {
    assertThat(MergeDisposition.glob("scripts/*", "scripts/test/fixtures/a.tsv")).isTrue();
    assertThat(MergeDisposition.glob("frontend/*.config.*", "frontend/a/b/c.config.ts")).isTrue();
    assertThat(MergeDisposition.glob("docker-compose*.yml", "docker-compose.yml")).isTrue();
    assertThat(MergeDisposition.glob("docker-compose*.yml", "config/docker-compose.yml"))
        .isFalse();
    assertThat(MergeDisposition.glob("gradlew", "gradlew.bat")).isFalse();
  }

  @Test
  void globStaysFastOnPathShapedToMakeBacktrackingMatcherExplode() {
    // Many stars and a long run of near-matches is the worst case for a naive or regex matcher.
    String path = "a".repeat(100_000) + "b";
    long started = System.nanoTime();
    assertThat(MergeDisposition.glob("*a*a*a*a*a*c", path)).isFalse();
    assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
  }

  private static MergeDisposition fromScriptCategory(final String category) {
    return switch (category) {
      case "auto-merge" -> MergeDisposition.AUTO_MERGE;
      case "ux-review" -> MergeDisposition.UX_REVIEW;
      case "manual" -> MergeDisposition.MANUAL;
      default -> throw new IllegalArgumentException("Unknown category in fixture: " + category);
    };
  }
}
