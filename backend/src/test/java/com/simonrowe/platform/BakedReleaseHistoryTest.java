package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BakedReleaseHistoryTest {

  private static final String UNIT_SEP = "\u001f";
  private static final String RECORD_SEP = "\u001e";

  @Test
  void parsesOneCommitPerRecord() {
    String raw = record(
        "840c311abcdef0123456789abcdef0123456789a",
        "1756200000",
        "docs: overhaul the README (#118)",
        "Rewrote it.\n\nAdded diagrams.",
        "\nREADME.md\ndocs/architecture.md\n")
        + record(
            "39e0f7aabcdef0123456789abcdef0123456789a",
            "1756100000",
            "feat: deploy automatically on merge to main (#116)",
            "",
            "\ndocker-compose.prod.yml\n");

    List<BakedRelease> releases = BakedReleaseHistory.parse(raw);

    assertThat(releases).hasSize(2);
    BakedRelease first = releases.get(0);
    assertThat(first.sha()).isEqualTo("840c311abcdef0123456789abcdef0123456789a");
    assertThat(first.shortSha()).isEqualTo("840c311");
    assertThat(first.commitTime()).isEqualTo(Instant.ofEpochSecond(1756200000L));
    assertThat(first.subject()).isEqualTo("docs: overhaul the README (#118)");
    assertThat(first.body()).isEqualTo("Rewrote it.\n\nAdded diagrams.");
    assertThat(first.filesChanged()).containsExactly("README.md", "docs/architecture.md");
    assertThat(releases.get(1).body()).isEmpty();
  }

  @Test
  void derivesTheConventionalCommitType() {
    assertThat(release("docs: overhaul the README").type()).isEqualTo("docs");
    assertThat(release("feat: deploy automatically").type()).isEqualTo("feat");
    assertThat(release("fix(api): stop the 500").type()).isEqualTo("fix");
    assertThat(release("perf: stop a 60s block").type()).isEqualTo("perf");
    assertThat(release("Merge pull request #7").type()).isEqualTo("other");
    assertThat(release("no colon here").type()).isEqualTo("other");
  }

  @Test
  void returnsEmptyForAbsentOrBlankHistory() {
    assertThat(BakedReleaseHistory.parse("")).isEmpty();
    assertThat(BakedReleaseHistory.parse("   \n ")).isEmpty();
  }

  @Test
  void skipsMalformedRecordsRatherThanFailing() {
    String raw = RECORD_SEP + "onlyonefield";

    assertThat(BakedReleaseHistory.parse(raw)).isEmpty();
  }

  @Test
  void skipsRecordWithAnUnparseableTimestamp() {
    String raw = record(
        "840c311abcdef0123456789abcdef0123456789a", "not-a-number", "feat: thing", "", "\n");

    assertThat(BakedReleaseHistory.parse(raw)).isEmpty();
  }

  private static BakedRelease release(final String subject) {
    String raw =
        record("840c311abcdef0123456789abcdef0123456789a", "1756200000", subject, "", "\n");
    return BakedReleaseHistory.parse(raw).get(0);
  }

  /** One {@code git log} record: a record separator, then the fields joined by unit separators. */
  private static String record(final String... fields) {
    return RECORD_SEP + String.join(UNIT_SEP, fields);
  }
}
