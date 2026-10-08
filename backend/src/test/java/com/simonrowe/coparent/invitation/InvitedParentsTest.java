package com.simonrowe.coparent.invitation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InvitedParentsTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "rhian@example.com|Rhian",
      "rhian.jones@example.com|Rhian Jones",
      "RHIAN_JONES+school@example.com|Rhian Jones",
      "r-jones2@example.com|R Jones",
      "1234@example.com|Your co-parent",
      "siân@example.com|Siân"
  })
  void namesAnInviteeFromTheirAddress(final String email, final String expected) {
    assertThat(InvitedParents.nameFromEmail(email)).isEqualTo(expected);
  }

  @Test
  void stayFastOnAnAddressShapedToBacktrack() {
    final String hostile = "a.".repeat(60_000) + "!".repeat(20_000) + "@example.com";

    final long started = System.nanoTime();
    final String name = InvitedParents.nameFromEmail(hostile);

    assertThat(name).hasSizeLessThanOrEqualTo(InvitedParents.MAX_NAME);
    assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
  }
}
