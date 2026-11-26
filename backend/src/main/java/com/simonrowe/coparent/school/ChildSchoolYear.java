package com.simonrowe.coparent.school;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.school.model.AcademicYear;
import com.simonrowe.school.model.YearGroups;
import java.time.LocalDate;
import java.time.Month;
import java.util.Optional;

/**
 * Which year group and class a linked child is in today.
 *
 * <p>The year group comes from the date of birth by the English rule: a school year runs from 1
 * September to 31 August, and a child starts Reception in the September after they turn four
 * (they are four on the 31 August before it). So it moves on every September with nobody editing
 * anything. The class does not: it is a fact the parent enters, and one entered for an earlier
 * academic year is treated as unknown.
 */
public final class ChildSchoolYear {

  /** Reception is the year a child is four on the 31 August before it. */
  private static final int RECEPTION_AGE = 4;
  private static final int LAST_PRIMARY_YEAR = 6;

  private ChildSchoolYear() {
  }

  /**
   * The year group a child born on a date is in on another date.
   *
   * @param dateOfBirth the child's date of birth
   * @param today the date to answer for
   * @return {@code Reception} or {@code Year 1} to {@code Year 6}, or empty for a child younger
   *     or older than primary school
   */
  public static Optional<String> yearGroupFor(final LocalDate dateOfBirth, final LocalDate today) {
    if (dateOfBirth == null || today == null) {
      return Optional.empty();
    }
    final int septemberYear = today.getMonthValue() >= Month.SEPTEMBER.getValue()
        ? today.getYear() : today.getYear() - 1;
    // Age on the 31 August before the school year starts: a 1 September birthday is the oldest
    // in its year and a 31 August one the youngest.
    final LocalDate cutoff = LocalDate.of(septemberYear, Month.AUGUST, 31);
    int age = cutoff.getYear() - dateOfBirth.getYear();
    if (dateOfBirth.plusYears(age).isAfter(cutoff)) {
      age--;
    }
    final int year = age - RECEPTION_AGE;
    if (year < 0 || year > LAST_PRIMARY_YEAR) {
      return Optional.empty();
    }
    return Optional.of(year == 0 ? "Reception" : "Year " + year);
  }

  /**
   * A linked child's year group today: the explicit one when the parent set it, otherwise the one
   * the date of birth gives.
   *
   * @param child the child
   * @param today the date to answer for
   * @return the year group, or empty when the child is not linked or not of primary age
   */
  public static Optional<String> yearGroupOf(final Child child, final LocalDate today) {
    if (child.schoolCalendar() == null) {
      return Optional.empty();
    }
    final String explicit = child.schoolCalendar().yearGroup();
    if (YearGroups.isValid(explicit)) {
      return Optional.of(explicit);
    }
    return yearGroupFor(child.dateOfBirth(), today);
  }

  /**
   * A linked child's class today, only when it was entered for the current academic year.
   *
   * @param child the child
   * @param today the date to answer for
   * @return the class name, or empty when unknown or entered for an earlier year
   */
  public static Optional<String> classOf(final Child child, final LocalDate today) {
    final Child.SchoolCalendarLink link = child.schoolCalendar();
    if (link == null || link.classTeacher() == null || link.classTeacher().isBlank()) {
      return Optional.empty();
    }
    if (!AcademicYear.current(today).equals(link.academicYear())) {
      return Optional.empty();
    }
    return Optional.of(link.classTeacher().strip());
  }
}
