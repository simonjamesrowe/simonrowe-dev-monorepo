package com.simonrowe.factory.cvefix.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SeverityTest {

  @Test
  void ranksCriticalAboveHighAboveMediumAboveLow() {
    assertThat(Severity.rank("CRITICAL")).isLessThan(Severity.rank("HIGH"));
    assertThat(Severity.rank("HIGH")).isLessThan(Severity.rank("MEDIUM"));
    assertThat(Severity.rank("MEDIUM")).isLessThan(Severity.rank("LOW"));
    assertThat(Severity.rank("LOW")).isLessThan(Severity.rank("INFO"));
  }

  @Test
  void ranksUnassignedNullAndUnknownLast() {
    int last = Severity.rank("INFO") + 1;
    assertThat(Severity.rank("UNASSIGNED")).isEqualTo(last);
    assertThat(Severity.rank(null)).isEqualTo(last);
    assertThat(Severity.rank("")).isEqualTo(last);
    assertThat(Severity.rank("   ")).isEqualTo(last);
    assertThat(Severity.rank("NOT_A_SEVERITY")).isEqualTo(last);
  }

  @Test
  void matchesCaseInsensitivelyAndIgnoresSurroundingWhitespace() {
    assertThat(Severity.rank("critical")).isEqualTo(Severity.rank("CRITICAL"));
    assertThat(Severity.rank("  High  ")).isEqualTo(Severity.rank("HIGH"));
  }
}
