package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchoolPropertiesTest {

  private SchoolProperties withSenders(
      final List<String> allow, final List<String> deny) {
    return new SchoolProperties(
        true, null, allow, deny, null, null, null, 0, null, null, 100, null, null);
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
            false, null, null, null, null, null, null, 0, null, null, 0, null, null);
    assertThat(defaults.enabled()).isFalse();
    assertThat(defaults.dailyTokenBudget()).isZero();
    assertThat(defaults.chatModel()).isEqualTo("gpt-5.6-luna");
    assertThat(defaults.guardrailModel()).isEqualTo("gpt-5-nano");
  }

  private SchoolProperties withPages(final String baseUrl, final List<String> extraPages) {
    return new SchoolProperties(
        true, null, List.of(), List.of(), null, baseUrl, null, 0, null, null, 0, null,
        extraPages);
  }

  @Test
  @DisplayName("the year-group pages are crawled by default")
  void yearGroupPagesAreOnByDefault() {
    // These seven return 200 and none of them is in the school's sitemap, so nothing else in
    // the crawl will ever reach them. Losing this default silently loses every year group's
    // teachers and PE days, with no error anywhere — which is exactly how it shipped.
    assertThat(withPages(null, null).extraPageUrls()).containsExactly(
        "https://www.kilmorieschool.co.uk/year-group-pages",
        "https://www.kilmorieschool.co.uk/year-one",
        "https://www.kilmorieschool.co.uk/year-two",
        "https://www.kilmorieschool.co.uk/year-three",
        "https://www.kilmorieschool.co.uk/year-4",
        "https://www.kilmorieschool.co.uk/year-five",
        "https://www.kilmorieschool.co.uk/year-six");
  }

  @Test
  @DisplayName("an empty configured list falls back to the default rather than crawling nothing")
  void emptyMeansDefault() {
    // application.yml passes ${SCHOOL_EXTRA_PAGE_URLS:}, and Spring binds an empty string to an
    // empty list. If empty meant "none", the shipped default would never once apply.
    assertThat(withPages(null, List.of()).extraPageUrls()).hasSize(7);
  }

  @Test
  @DisplayName("configured paths resolve against the website base URL")
  void pathsResolveAgainstTheBase() {
    assertThat(withPages("https://example.test", List.of("/year-six", "year-five"))
        .extraPageUrls())
        .containsExactly("https://example.test/year-six", "https://example.test/year-five");
  }

  @Test
  @DisplayName("an absolute configured URL is left alone")
  void absoluteUrlsPassThrough() {
    assertThat(withPages("https://example.test", List.of("https://elsewhere.test/page"))
        .extraPageUrls())
        .containsExactly("https://elsewhere.test/page");
  }

  @Test
  @DisplayName("a base URL with a trailing slash does not produce a doubled slash")
  void trailingSlashOnTheBaseIsNormalised() {
    final SchoolProperties properties = withPages("https://example.test/", List.of("/year-six"));
    assertThat(properties.extraPageUrls()).containsExactly("https://example.test/year-six");
    // staffListUrl is built by the same concatenation and had the same latent bug.
    assertThat(properties.staffListUrl()).isEqualTo("https://example.test/our-school/our-staff");
  }

  @Test
  @DisplayName("blank and duplicate entries are dropped")
  void blankAndDuplicateEntriesDropped() {
    assertThat(withPages("https://example.test", java.util.Arrays.asList(
        "/year-six", "  ", null, "/year-six")).extraPageUrls())
        .containsExactly("https://example.test/year-six");
  }
}
