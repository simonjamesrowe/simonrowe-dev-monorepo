package com.simonrowe.school.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The published staff list, as crawled from the school's own staff page. */
class StaffDirectoryTest {

  private static StaffDirectory withNames(final String... names) {
    final StaffDirectory directory = new StaffDirectory();
    directory.replace(new LinkedHashSet<>(Set.of(names)));
    return directory;
  }

  @Test
  @DisplayName("a published full name is recognised regardless of case or padding")
  void recognisesPublishedName() {
    final StaffDirectory directory = withNames("Dennis Irwin", "Ada Lovelace");

    assertThat(directory.isStaffName("Dennis Irwin")).isTrue();
    assertThat(directory.isStaffName("dennis irwin")).isTrue();
    assertThat(directory.isStaffName("  Dennis Irwin  ")).isTrue();
    assertThat(directory.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("someone not on the staff page is not staff")
  void rejectsAnUnpublishedName() {
    assertThat(withNames("Dennis Irwin").isStaffName("Amelia Watts")).isFalse();
  }

  @Test
  @DisplayName("surnames are indexed separately, so 'Mr Irwin' resolves")
  void indexesSurnames() {
    final StaffDirectory directory = withNames("Dennis Irwin", "Ada Lovelace");

    assertThat(directory.isStaffSurname("Irwin")).isTrue();
    assertThat(directory.isStaffSurname("lovelace")).isTrue();
    assertThat(directory.isStaffSurname("Watts")).isFalse();
  }

  @Test
  @DisplayName("a single-word entry contributes no surname")
  void singleWordEntryHasNoSurname() {
    // "Reception" or "Office" appear on staff pages as headings. Indexing them as surnames
    // would make every mention of the word resolve to a member of staff.
    final StaffDirectory directory = withNames("Reception");

    assertThat(directory.isStaffName("Reception")).isTrue();
    assertThat(directory.isStaffSurname("Reception")).isFalse();
  }

  @Test
  @DisplayName("an empty directory recognises nobody, rather than everybody")
  void emptyDirectoryRecognisesNobody() {
    final StaffDirectory directory = new StaffDirectory();

    assertThat(directory.size()).isZero();
    assertThat(directory.isStaffName("Dennis Irwin")).isFalse();
    assertThat(directory.isStaffSurname("Irwin")).isFalse();
  }

  @Test
  @DisplayName("blank and null entries are dropped rather than stored")
  void dropsBlankEntries() {
    final Set<String> names = new LinkedHashSet<>();
    names.add("Dennis Irwin");
    names.add("   ");
    names.add(null);
    final StaffDirectory directory = new StaffDirectory();

    directory.replace(names);

    assertThat(directory.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("replace swaps the whole list, so a departed member of staff stops resolving")
  void replaceSwapsTheWholeList() {
    final StaffDirectory directory = withNames("Dennis Irwin");

    directory.replace(Set.of("Ada Lovelace"));

    assertThat(directory.isStaffName("Dennis Irwin")).isFalse();
    assertThat(directory.isStaffSurname("Irwin")).isFalse();
    assertThat(directory.isStaffName("Ada Lovelace")).isTrue();
  }

  @Test
  @DisplayName("a null or blank query is never a match")
  void nullQueryNeverMatches() {
    final StaffDirectory directory = withNames("Dennis Irwin");

    assertThat(directory.isStaffName(null)).isFalse();
    assertThat(directory.isStaffName("")).isFalse();
    assertThat(directory.isStaffSurname(null)).isFalse();
  }
}
