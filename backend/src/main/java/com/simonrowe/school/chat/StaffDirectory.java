package com.simonrowe.school.chat;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * The set of names the school itself publishes, which are therefore not private.
 *
 * <p>Populated by the website crawler from the school's staff page. Starts <b>empty</b>, and an
 * empty directory means every name is treated as non-staff — so the name gate blocks everything
 * until the crawl has run. That is the fail-closed direction: the alternative, treating an
 * unpopulated directory as "no restrictions", would make the gate silently inert exactly when it
 * has least information.
 */
@Component
public class StaffDirectory {

  private final AtomicReference<Set<String>> fullNames = new AtomicReference<>(Set.of());
  private final AtomicReference<Set<String>> surnames = new AtomicReference<>(Set.of());

  /**
   * Replaces the directory with a freshly crawled staff list.
   *
   * @param names full names as published, e.g. {@code Dennis Irwin}
   */
  public void replace(final Set<String> names) {
    final Set<String> lowerFull = new LinkedHashSet<>();
    final Set<String> lowerSurnames = new LinkedHashSet<>();
    for (String name : names) {
      final String trimmed = name == null ? "" : name.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      lowerFull.add(trimmed.toLowerCase(Locale.ROOT));
      final String[] parts = trimmed.split("\\s+");
      if (parts.length > 1) {
        lowerSurnames.add(parts[parts.length - 1].toLowerCase(Locale.ROOT));
      }
    }
    fullNames.set(Set.copyOf(lowerFull));
    surnames.set(Set.copyOf(lowerSurnames));
  }

  /**
   * Whether a full name belongs to published staff.
   *
   * @param name a candidate full name
   * @return true when the school publishes this person
   */
  public boolean isStaffName(final String name) {
    return name != null && fullNames.get().contains(name.toLowerCase(Locale.ROOT).trim());
  }

  /**
   * Whether a surname belongs to published staff, for "Mrs Smith" forms.
   *
   * @param surname a candidate surname
   * @return true when a member of staff has that surname
   */
  public boolean isStaffSurname(final String surname) {
    return surname != null && surnames.get().contains(surname.toLowerCase(Locale.ROOT).trim());
  }

  /**
   * How many staff are known. Zero means the crawl has not run and the gate blocks everything.
   *
   * @return the number of published staff names
   */
  public int size() {
    return fullNames.get().size();
  }
}
