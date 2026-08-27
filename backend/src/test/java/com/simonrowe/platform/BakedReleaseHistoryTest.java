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
    String raw = RECORD_SEP
        + "840c311abcdef0123456789abcdef0123456789a" + UNIT_SEP
        + "1756200000" + UNIT_SEP
        + "docs: overhaul the README (#118)" + UNIT_SEP
        + "Rewrote it.\n\nAdded diagrams." + UNIT_SEP
        + "\nREADME.md\ndocs/architecture.md\n"
        + RECORD_SEP
        + "39e0f7aabcdef0123456789abcdef0123456789a" + UNIT_SEP
        + "1756100000" + UNIT_SEP
        + "feat: deploy automatically on merge to main (#116)" + UNIT_SEP
        + "" + UNIT_SEP
        + "\ndocker-compose.prod.yml\n";

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
    String raw = RECORD_SEP
        + "840c311abcdef0123456789abcdef0123456789a" + UNIT_SEP
        + "not-a-number" + UNIT_SEP
        + "feat: thing" + UNIT_SEP + "" + UNIT_SEP + "\n";

    assertThat(BakedReleaseHistory.parse(raw)).isEmpty();
  }

  private static BakedRelease release(final String subject) {
    String raw = RECORD_SEP
        + "840c311abcdef0123456789abcdef0123456789a" + UNIT_SEP
        + "1756200000" + UNIT_SEP
        + subject + UNIT_SEP + "" + UNIT_SEP + "\n";
    return BakedReleaseHistory.parse(raw).get(0);
  }
}
