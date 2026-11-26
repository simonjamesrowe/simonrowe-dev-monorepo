package com.simonrowe.coparent.model;

import java.time.Instant;
import java.time.LocalDate;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Private child profile owned by one family.
 *
 * @param schoolCalendar how this child is found in Term Time's school calendar, or null when the
 *     family has not linked them, in which case no school event is ever suggested for them
 */
@Document("children")
public record Child(
    @Id ObjectId id,
    ObjectId familyId,
    String fullName,
    LocalDate dateOfBirth,
    String school,
    String medicalNotes,
    String avatarUrl,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt,
    SchoolCalendarLink schoolCalendar
) {

  /** A child with no school calendar link. */
  public Child(
      final ObjectId id,
      final ObjectId familyId,
      final String fullName,
      final LocalDate dateOfBirth,
      final String school,
      final String medicalNotes,
      final String avatarUrl,
      final Instant deletedAt,
      final Instant createdAt,
      final Instant updatedAt) {
    this(id, familyId, fullName, dateOfBirth, school, medicalNotes, avatarUrl, deletedAt,
        createdAt, updatedAt, null);
  }

  /**
   * Which class a child is in, so school events can be suggested for them.
   *
   * <p>The school names its classes after their teachers ("Elis's class trip"), and a year
   * group has more than one class, so the year group alone would put the other class's trips on
   * this child's calendar. The class changes every September, which is why the academic year it
   * was entered for is kept: a class from last year is treated as unknown rather than trusted.
   *
   * @param classTeacher the class's name as the school writes it, usually the teacher's first
   *     name ({@code Elis}); null when not known
   * @param yearGroup an explicit year group ({@code Year 3}), or null to work it out from the date
   *     of birth, which also moves the child up each September without anyone editing it
   * @param academicYear the academic year {@code classTeacher} was entered for, e.g.
   *     {@code 2026/27}
   */
  public record SchoolCalendarLink(String classTeacher, String yearGroup, String academicYear) {
  }
}
