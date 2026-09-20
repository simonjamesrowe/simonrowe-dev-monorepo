package com.simonrowe.coparent.family;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.Family;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.FamilyRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Family-scoped child profile operations. */
@Service
public class ChildService {

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
        school == null ? current.school() : school,
        medicalNotes == null ? current.medicalNotes() : medicalNotes,
        current.avatarUrl(), null, current.createdAt(), Instant.now()));
    audits.record(saved.familyId(), "child", saved.id(), "update",
        Map.of("fullName", name, "dateOfBirth", birthDate.toString()));
    return saved;
  }

  /** Soft-deletes a child and removes its active family reference. */
  public void delete(final ObjectId childId) {
    final Child child = get(childId);
    final Instant now = Instant.now();
    children.save(new Child(child.id(), child.familyId(), child.fullName(), child.dateOfBirth(),
        child.school(), child.medicalNotes(), child.avatarUrl(), now, child.createdAt(), now));
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
}
