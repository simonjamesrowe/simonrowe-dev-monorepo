package com.simonrowe.coparent.family;

import com.simonrowe.coparent.model.Family;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.FamilyRepository;
import com.simonrowe.coparent.persistence.OnboardingStateRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.model.OnboardingState;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import com.simonrowe.coparent.shared.CoparentIdentity;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Family and parent-profile use cases rooted in verified Auth0 identity. */
@Service
public class FamilyService {

  private static final String ACTIVE = CoparentAccessPolicy.ACTIVE;

  private final FamilyRepository families;
  private final ParentRepository parents;
  private final OnboardingStateRepository onboardingStates;
  private final CoparentAccessPolicy access;
  private final CoparentIdentity identity;
  private final CoparentAuditService audits;

  /** Creates a family service with its persistence and policy collaborators. */
  public FamilyService(
      final FamilyRepository families,
      final ParentRepository parents,
      final OnboardingStateRepository onboardingStates,
      final CoparentAccessPolicy access,
      final CoparentIdentity identity,
      final CoparentAuditService audits) {
    this.families = families;
    this.parents = parents;
    this.onboardingStates = onboardingStates;
    this.access = access;
    this.identity = identity;
    this.audits = audits;
  }

  /** Returns all profiles, creating a single unassigned profile on first use. */
  public CurrentUser currentUser() {
    List<Parent> profiles = parents.findByAuth0IdAndStatus(identity.subject(), ACTIVE);
    final String verifiedEmail;
    if (profiles.isEmpty()) {
      profiles = List.of(createInitialProfile(""));
      verifiedEmail = identity.email();
    } else {
      final String tokenEmail = identity.emailOrNull();
      verifiedEmail = tokenEmail == null
          ? profiles.stream().map(Parent::email)
              .filter(email -> email != null && !email.isBlank())
              .findFirst().orElseGet(identity::email)
          : tokenEmail;
      final Instant now = Instant.now();
      profiles = profiles.stream().map(parent -> new Parent(
          parent.id(), parent.auth0Id(), parent.familyId(), parent.fullName(), parent.email(),
          parent.role(), parent.status(), parent.color(), parent.avatarUrl(), now,
          parent.createdAt(), now)).map(parents::save).toList();
    }
    return new CurrentUser(identity.subject(), verifiedEmail, profiles,
        profiles.stream().allMatch(profile -> profile.familyId() == null));
  }

  /** Creates the initial profile only when the caller has none. */
  public Parent createProfile(final String fullName) {
    final List<Parent> existing = parents.findByAuth0IdAndStatus(identity.subject(), ACTIVE);
    if (!existing.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Profile already exists");
    }
    return createInitialProfile(requireName(fullName));
  }

  /** Updates the caller's name consistently across each family membership. */
  public List<Parent> updateProfile(final String fullName) {
    final String name = requireName(fullName);
    final Instant now = Instant.now();
    final List<Parent> existing = parents.findByAuth0IdAndStatus(identity.subject(), ACTIVE);
    if (existing.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found");
    }
    return existing.stream().map(parent -> {
      final Parent updated = new Parent(parent.id(), parent.auth0Id(), parent.familyId(), name,
          identity.email(), parent.role(), parent.status(), parent.color(), parent.avatarUrl(),
          parent.lastSignedInAt(), parent.createdAt(), now);
      final Parent saved = parents.save(updated);
      audits.record(saved.familyId(), "parent", saved.id(), "update-profile",
          Map.of("fullName", name));
      return saved;
    }).toList();
  }

  /** Creates a family and links a new or unassigned profile as its primary parent. */
  public Family createFamily(final String rawName, final String timeZone, final String fullName) {
    final String name = requireName(rawName);
    validateTimeZone(timeZone);
    final Instant now = Instant.now();
    Family family = families.save(new Family(null, name, timeZone, List.of(), List.of(), List.of(),
        null, now, now));

    final Parent base = parents.findFirstByAuth0IdAndFamilyIdIsNull(identity.subject())
        .orElse(null);
    final Parent parent = parents.save(new Parent(
        base == null ? null : base.id(),
        identity.subject(),
        family.id(),
        fullName == null || fullName.isBlank()
            ? base == null ? "" : base.fullName()
            : fullName.trim(),
        identity.email(),
        CoparentAccessPolicy.PRIMARY,
        ACTIVE,
        base == null ? null : base.color(),
        base == null ? null : base.avatarUrl(),
        now,
        base == null ? now : base.createdAt(),
        now));
    family = families.save(new Family(family.id(), family.name(), family.timeZone(),
        List.of(parent.id()), family.childIds(), family.invitationIds(), null,
        family.createdAt(), now));
    onboardingStates.save(new OnboardingState(null, family.id(), "child", List.of("family"),
        false, now, now, now));
    audits.record(family.id(), "family", family.id(), "create",
        Map.of("name", name, "timeZone", timeZone, "parentId", parent.id().toHexString()));
    return family;
  }

  /** Lists active families reached only through the caller's memberships. */
  public List<Family> listFamilies() {
    final List<ObjectId> familyIds = parents.findByAuth0IdAndStatus(identity.subject(), ACTIVE)
        .stream().map(Parent::familyId).filter(java.util.Objects::nonNull).toList();
    return familyIds.isEmpty() ? List.of() : families.findByIdInAndDeletedAtIsNull(familyIds);
  }

  /** Returns one active family when the caller is a member. */
  public Family getFamily(final ObjectId familyId) {
    access.requireMember(familyId);
    return families.findByIdAndDeletedAtIsNull(familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Family not found"));
  }

  /** Applies a partial family update after validating its time zone. */
  public Family updateFamily(
      final ObjectId familyId,
      final String rawName,
      final String timeZone) {
    final Family current = getFamily(familyId);
    final String name = rawName == null ? current.name() : requireName(rawName);
    final String zone = timeZone == null ? current.timeZone() : timeZone;
    validateTimeZone(zone);
    final Family saved = families.save(new Family(current.id(), name, zone, current.parentIds(),
        current.childIds(), current.invitationIds(), null, current.createdAt(), Instant.now()));
    audits.record(familyId, "family", familyId, "update",
        Map.of("name", name, "timeZone", zone));
    return saved;
  }

  /** Soft-deletes a family while retaining recoverable history. */
  public void deleteFamily(final ObjectId familyId) {
    final Family current = getFamily(familyId);
    final Instant now = Instant.now();
    families.save(new Family(current.id(), current.name(), current.timeZone(), current.parentIds(),
        current.childIds(), current.invitationIds(), now, current.createdAt(), now));
    audits.record(familyId, "family", familyId, "delete", Map.of("deletedAt", now));
  }

  /** Lists active parent profiles for an authorised family. */
  public List<Parent> listParents(final ObjectId familyId) {
    access.requireMember(familyId);
    return parents.findByFamilyIdAndStatus(familyId, ACTIVE);
  }

  /** Changes another parent's role while preserving at least one primary parent. */
  public Parent updateRole(final ObjectId parentId, final String role) {
    if (!List.of("primary", "co-parent").contains(role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid parent role");
    }
    final Parent target = parents.findById(parentId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent not found"));
    if (target.familyId() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent not found");
    }
    final Parent actor = access.requirePrimary(target.familyId());
    if (actor.id().equals(target.id())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change your own role");
    }
    if (CoparentAccessPolicy.PRIMARY.equals(target.role())
        && !CoparentAccessPolicy.PRIMARY.equals(role)
        && parents.countByFamilyIdAndRoleAndStatus(
            target.familyId(), CoparentAccessPolicy.PRIMARY, ACTIVE) <= 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A family must retain a primary parent");
    }
    final Parent saved = parents.save(new Parent(target.id(), target.auth0Id(), target.familyId(),
        target.fullName(), target.email(), role, target.status(), target.color(),
        target.avatarUrl(), target.lastSignedInAt(), target.createdAt(), Instant.now()));
    audits.record(saved.familyId(), "parent", saved.id(), "update-role", Map.of("role", role));
    return saved;
  }

  private Parent createInitialProfile(final String fullName) {
    final Instant now = Instant.now();
    final Parent saved = parents.save(new Parent(null, identity.subject(), null, fullName,
        identity.email(), "co-parent", ACTIVE, null, null, now, now, now));
    audits.record(null, "parent", saved.id(), "create-initial", Map.of("status", ACTIVE));
    return saved;
  }

  private static String requireName(final String name) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required");
    }
    return name.trim();
  }

  private static void validateTimeZone(final String timeZone) {
    try {
      ZoneId.of(timeZone);
    } catch (java.time.DateTimeException | NullPointerException exception) {
      // DateTimeException covers both an unknown region (ZoneRulesException) and a malformed
      // id such as "Europe/", which previously escaped as a 500.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid time zone", exception);
    }
  }

  /** Current subject and all of its family profiles. */
  public record CurrentUser(
      String auth0Id,
      String email,
      List<Parent> profiles,
      boolean newUser
  ) {
  }
}
