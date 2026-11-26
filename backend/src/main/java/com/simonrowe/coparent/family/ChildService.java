package com.simonrowe.coparent.family;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.Family;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.FamilyRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import com.simonrowe.school.model.AcademicYear;
import com.simonrowe.school.model.YearGroups;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Family-scoped child profile operations. */
@Service
public class ChildService {

  /** A first name or a class name: letters, spaces, hyphens and apostrophes, bounded. */
  private static final Pattern CLASS_NAME =
      Pattern.compile("[\\p{L}][\\p{L} '’-]{0,39}+");

  private final ChildRepository children;
  private final FamilyRepository families;
  private final CoparentAccessPolicy access;
  private final CoparentAuditService audits;

  public ChildService(
      final ChildRepository children,
      final FamilyRepository families,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits) {
    this.children = children;
    this.families = families;
    this.access = access;
    this.audits = audits;
  }

  /** Adds a child to an authorised family. */
  public Child create(
      final ObjectId familyId,
      final String rawName,
      final LocalDate dateOfBirth,
      final String school,
      final String medicalNotes) {
    access.requireMember(familyId);
    final Family family = requireFamily(familyId);
    final String name = requireName(rawName);
    if (dateOfBirth == null || dateOfBirth.isAfter(LocalDate.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date of birth");
    }
    final Instant now = Instant.now();
    final Child saved = children.save(new Child(null, familyId, name, dateOfBirth, school,
        medicalNotes, null, null, now, now));
    final List<ObjectId> childIds = new ArrayList<>(family.childIds());
    childIds.add(saved.id());
    families.save(new Family(family.id(), family.name(), family.timeZone(), family.parentIds(),
        childIds, family.invitationIds(), family.deletedAt(), family.createdAt(), now));
    audits.record(familyId, "child", saved.id(), "create",
        Map.of("fullName", name, "dateOfBirth", dateOfBirth.toString()));
    return saved;
  }

  /** Lists active children from one authorised family. */
  public List<Child> list(final ObjectId familyId) {
    access.requireMember(familyId);
    requireFamily(familyId);
    return children.findByFamilyIdAndDeletedAtIsNull(familyId);
  }

  /** Returns an active child only through one of the caller's memberships. */
  public Child get(final ObjectId childId) {
    final Child child = children.findById(childId)
        .filter(candidate -> candidate.deletedAt() == null)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Child not found"));
    access.requireMember(child.familyId());
    return child;
  }

  /** Updates supported private child-profile fields. */
  public Child update(
      final ObjectId childId,
      final String rawName,
      final LocalDate dateOfBirth,
      final String school,
      final String medicalNotes) {
    final Child current = get(childId);
    final String name = rawName == null ? current.fullName() : requireName(rawName);
    final LocalDate birthDate = dateOfBirth == null ? current.dateOfBirth() : dateOfBirth;
    if (birthDate.isAfter(LocalDate.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date of birth");
    }
    final Child saved = children.save(new Child(current.id(), current.familyId(), name, birthDate,
        school == null ? current.school() : blankToNull(school),
        medicalNotes == null ? current.medicalNotes() : blankToNull(medicalNotes),
        current.avatarUrl(), null, current.createdAt(), Instant.now(),
        current.schoolCalendar()));
    audits.record(saved.familyId(), "child", saved.id(), "update",
        Map.of("fullName", name, "dateOfBirth", birthDate.toString()));
    return saved;
  }

  /**
   * Links a child to the school calendar, or updates the link, so school events can be offered
   * for them. The class is recorded against the current academic year, because it changes every
   * September and last year's class must not decide this year's events.
   *
   * @param childId the child
   * @param classTeacher the class's name as the school writes it, usually the teacher's first
   *     name; blank when not known
   * @param yearGroup an explicit year group, or blank to work it out from the date of birth
   * @param today the date, for the academic year
   * @return the child as saved
   */
  public Child linkSchoolCalendar(final ObjectId childId, final String classTeacher,
      final String yearGroup, final LocalDate today) {
    final Child current = get(childId);
    final String className = classTeacher == null || classTeacher.isBlank() ? null
        : classTeacher.strip();
    if (className != null && !CLASS_NAME.matcher(className).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Write the class as the school does, such as the teacher's first name");
    }
    final String year = yearGroup == null || yearGroup.isBlank() ? null : yearGroup.strip();
    if (year != null && !YearGroups.isValid(year)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown year group");
    }
    final Child.SchoolCalendarLink link =
        new Child.SchoolCalendarLink(className, year, AcademicYear.current(today));
    final Child saved = children.save(withSchoolCalendar(current, link));
    audits.record(saved.familyId(), "child", saved.id(), "link_school_calendar",
        Map.of("yearGroup", year == null ? "from date of birth" : year));
    return saved;
  }

  /** Stops offering school events for a child. Suggestions already made stay as they are. */
  public Child unlinkSchoolCalendar(final ObjectId childId) {
    final Child saved = children.save(withSchoolCalendar(get(childId), null));
    audits.record(saved.familyId(), "child", saved.id(), "unlink_school_calendar", Map.of());
    return saved;
  }

  private static Child withSchoolCalendar(final Child child,
      final Child.SchoolCalendarLink link) {
    return new Child(child.id(), child.familyId(), child.fullName(), child.dateOfBirth(),
        child.school(), child.medicalNotes(), child.avatarUrl(), child.deletedAt(),
        child.createdAt(), Instant.now(), link);
  }

  /** Soft-deletes a child and removes its active family reference. */
  public void delete(final ObjectId childId) {
    final Child child = get(childId);
    final Instant now = Instant.now();
    children.save(new Child(child.id(), child.familyId(), child.fullName(), child.dateOfBirth(),
        child.school(), child.medicalNotes(), child.avatarUrl(), now, child.createdAt(), now,
        child.schoolCalendar()));
    final Family family = requireFamily(child.familyId());
    final List<ObjectId> childIds = family.childIds().stream()
        .filter(id -> !id.equals(child.id())).toList();
    families.save(new Family(family.id(), family.name(), family.timeZone(), family.parentIds(),
        childIds, family.invitationIds(), family.deletedAt(), family.createdAt(), now));
    audits.record(child.familyId(), "child", child.id(), "delete", Map.of("deletedAt", now));
  }

  private Family requireFamily(final ObjectId familyId) {
    return families.findByIdAndDeletedAtIsNull(familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Family not found"));
  }

  private static String requireName(final String name) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Child name is required");
    }
    return name.trim();
  }

  /** A present-but-blank value clears the field; an absent one (null) leaves it unchanged. */
  private static String blankToNull(final String value) {
    return value.isBlank() ? null : value.trim();
  }
}
