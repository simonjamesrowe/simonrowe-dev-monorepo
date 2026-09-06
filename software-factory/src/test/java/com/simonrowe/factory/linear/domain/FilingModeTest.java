package com.simonrowe.factory.linear.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FilingModeTest {

  @Test
  @DisplayName("a status update never creates an issue; every other mode may")
  void onlyStatusUpdateNeverCreates() {
    assertThat(FilingMode.STATUS_UPDATE.mayCreate()).isFalse();
    assertThat(FilingMode.OCCURRENCE.mayCreate()).isTrue();
    assertThat(FilingMode.REFRESH.mayCreate()).isTrue();
    assertThat(FilingMode.ROLLING.mayCreate()).isTrue();
  }

  @Test
  @DisplayName("refresh and rolling rewrite the body; occurrence and status update comment")
  void bodyRewritingModes() {
    assertThat(FilingMode.REFRESH.rewritesBody()).isTrue();
    assertThat(FilingMode.ROLLING.rewritesBody()).isTrue();
    assertThat(FilingMode.OCCURRENCE.rewritesBody()).isFalse();
    assertThat(FilingMode.STATUS_UPDATE.rewritesBody()).isFalse();
  }

  @Test
  @DisplayName("only rolling reopens a completed issue instead of filing a replacement")
  void onlyRollingReopens() {
    assertThat(FilingMode.ROLLING.reopensCompleted()).isTrue();
    assertThat(FilingMode.OCCURRENCE.reopensCompleted()).isFalse();
    assertThat(FilingMode.REFRESH.reopensCompleted()).isFalse();
    assertThat(FilingMode.STATUS_UPDATE.reopensCompleted()).isFalse();
  }

  @Test
  @DisplayName("the seven-argument constructor still means today's behaviour")
  void sevenArgumentConstructorDefaultsToOccurrence() {
    IssueFiling filing = new IssueFiling("deploy", List.of("a"), "t", "b", "d", "run-1", "wf-1");

    assertThat(filing.mode()).isEqualTo(FilingMode.OCCURRENCE);
  }

  @Test
  @DisplayName("a null mode is never allowed to mean 'no behaviour'")
  void nullModeDefaultsToOccurrence() {
    IssueFiling filing =
        new IssueFiling("deploy", List.of("a"), "t", "b", "d", "run-1", "wf-1", null);

    assertThat(filing.mode()).isEqualTo(FilingMode.OCCURRENCE);
  }
}
