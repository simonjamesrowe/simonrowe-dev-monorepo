package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchoolPropertiesTest {

  private SchoolProperties withSenders(
      final List<String> allow, final List<String> deny) {
    return new SchoolProperties(
        true, null, allow, deny, null, null, null, 0, null, null, 100, null);
  }

  @Test
  @DisplayName("an allowed sender domain matches")
  void allowsTheSchoolDomain() {
    final SchoolProperties properties =
        withSenders(List.of("kilmorie.lewisham.sch.uk"), List.of());
    assertThat(properties.allowsSender("info@kilmorie.lewisham.sch.uk")).isTrue();
  }

  @Test
  @DisplayName("a sender using the school's NAME but not its address is rejected")
  void displayNameCannotGetYouIn() {
    // This is the real case, not a hypothetical: system@insighttracking.com sends mail whose
    // display name is "Kilmorie Primary School". Any allowlist that matched on the name would
    // admit it, and would admit anything else willing to set the same display name.
    final SchoolProperties properties =
        withSenders(List.of("kilmorie.lewisham.sch.uk"), List.of());
    assertThat(properties.allowsSender("system@insighttracking.com")).isFalse();
    assertThat(properties.allowsSender("Kilmorie Primary School")).isFalse();
  }

  @Test
  @DisplayName("the denylist beats the allowlist")
  void denyBeatsAllow() {
    final SchoolProperties properties = withSenders(
        List.of("kilmorie.lewisham.sch.uk", "parentpay.com"), List.of("parentpay.com"));
    assertThat(properties.allowsSender("platform@parentpay.com")).isFalse();
    assertThat(properties.allowsSender("info@kilmorie.lewisham.sch.uk")).isTrue();
  }

  @Test
  @DisplayName("an empty allowlist admits nobody")
  void emptyAllowlistAdmitsNobody() {
    // Fail closed. An unconfigured allowlist must not mean "ingest the whole mailbox".
    final SchoolProperties properties = withSenders(List.of(), List.of());
    assertThat(properties.allowsSender("info@kilmorie.lewisham.sch.uk")).isFalse();
  }

  @Test
  @DisplayName("blank and null addresses are rejected rather than matching a blank rule")
  void blankAddressesRejected() {
    final SchoolProperties properties =
        withSenders(List.of("kilmorie.lewisham.sch.uk"), List.of());
    assertThat(properties.allowsSender(null)).isFalse();
    assertThat(properties.allowsSender("   ")).isFalse();
  }

  @Test
  @DisplayName("the feature is off and the budget is zero unless configured")
  void defaultsAreInert() {
    final SchoolProperties defaults =
        new SchoolProperties(
            false, null, null, null, null, null, null, 0, null, null, 0, null);
    assertThat(defaults.enabled()).isFalse();
    assertThat(defaults.dailyTokenBudget()).isZero();
    assertThat(defaults.chatModel()).isEqualTo("gpt-5.6-luna");
    assertThat(defaults.guardrailModel()).isEqualTo("gpt-5-nano");
  }
}
