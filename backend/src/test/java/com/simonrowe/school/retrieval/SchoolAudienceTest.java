package com.simonrowe.school.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.school.model.Visibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchoolAudienceTest {

  @Test
  @DisplayName("an anonymous caller can never reach restricted content")
  void anonymousSeesPublicOnly() {
    assertThat(SchoolAudience.anonymous().visibilities())
        .containsExactly(Visibility.PUBLIC)
        .doesNotContain(Visibility.RESTRICTED);
  }

  @Test
  @DisplayName("a school member sees both tiers")
  void memberSeesBoth() {
    assertThat(SchoolAudience.schoolMember().visibilities())
        .containsExactlyInAnyOrder(Visibility.PUBLIC, Visibility.RESTRICTED);
  }

  @Test
  @DisplayName("only anonymous answers are name-checked on the way out")
  void onlyAnonymousAnswersAreChecked() {
    assertThat(SchoolAudience.anonymous().requiresPublicAnswerCheck()).isTrue();
    assertThat(SchoolAudience.schoolMember().requiresPublicAnswerCheck()).isFalse();
  }

  @Test
  @DisplayName("no audience can be constructed other than the two factories")
  void thereAreOnlyTwoAudiences() {
    // The tier a request may reach must never be derived from anything a client sent. Keeping
    // the constructor private is the mechanism; this test is what stops someone adding a public
    // one "just for tests" and leaving the door open.
    assertThat(SchoolAudience.class.getConstructors()).isEmpty();
  }

  @Test
  @DisplayName("an audience always grants at least the public tier")
  void neverEmpty() {
    // An empty visibility list would produce an `in []` filter, which silently matches nothing.
    // That fails closed, but it would present as "the assistant knows nothing" rather than as
    // an access error, which is a miserable thing to debug.
    assertThat(SchoolAudience.anonymous().visibilities()).isNotEmpty();
    assertThat(SchoolAudience.schoolMember().visibilities()).isNotEmpty();
  }
}
