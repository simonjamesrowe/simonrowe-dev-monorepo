package com.simonrowe.coparent.invitation;

import com.simonrowe.coparent.model.Family;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.FamilyRepository;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import com.simonrowe.coparent.shared.CoparentIdentity;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Secure invitation lifecycle, including atomic single-use acceptance. */
@Service
public class InvitationService {

  private static final Logger log = LoggerFactory.getLogger(InvitationService.class);
  private static final String PENDING = "pending";

  private final InvitationRepository invitations;
  private final FamilyRepository families;
  private final ParentRepository parents;
  private final CoparentAccessPolicy access;
  private final CoparentIdentity identity;
  private final CoparentAuditService audits;
  private final InvitationMailer mailer;
  private final MongoTemplate mongoTemplate;

  /** Creates the invitation service with persistence, security and delivery dependencies. */
  public InvitationService(
      final InvitationRepository invitations,
      final FamilyRepository families,
      final ParentRepository parents,
      final CoparentAccessPolicy access,
      final CoparentIdentity identity,
      final CoparentAuditService audits,
      final InvitationMailer mailer,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this.invitations = invitations;
    this.families = families;
    this.parents = parents;
    this.access = access;
    this.identity = identity;
    this.audits = audits;
    this.mailer = mailer;
    this.mongoTemplate = mongoTemplate;
  }

  /** Creates a seven-day invitation for a non-member email. */
  public Invitation create(final ObjectId familyId, final String rawEmail, final String role) {
    final Parent inviter = access.requirePrimary(familyId);
    final Family family = requireFamily(familyId);
    final String email = normaliseEmail(rawEmail);
    validateRole(role);
    if (parents.existsByFamilyIdAndEmailIgnoreCaseAndStatus(
        familyId, email, CoparentAccessPolicy.ACTIVE)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "This email is already a member of this family");
    }
    if (invitations.existsByFamilyIdAndEmailAndStatus(familyId, email, PENDING)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "A pending invitation already exists for this email");
    }
    final Instant now = Instant.now();
    final Invitation saved = invitations.save(new Invitation(null, familyId, email, role, PENDING,
        UUID.randomUUID().toString(), now, now.plus(7, ChronoUnit.DAYS), null, null, null, null,
        now, now));
    final List<ObjectId> invitationIds = new ArrayList<>(family.invitationIds());
    invitationIds.add(saved.id());
    families.save(new Family(family.id(), family.name(), family.timeZone(), family.parentIds(),
        family.childIds(), invitationIds, family.deletedAt(), family.createdAt(), now));
    deliver(saved, inviter, family);
    audits.record(familyId, "invitation", saved.id(), "create",
        Map.of("email", email, "role", role, "expiresAt", saved.expiresAt()));
    return saved;
  }

  /** Lists family invitations with expired pending rows normalised first. */
  public List<Invitation> list(final ObjectId familyId) {
    access.requireMember(familyId);
    return invitations.findByFamilyIdOrderBySentAtDesc(familyId).stream()
        .map(this::expireIfRequired)
        .toList();
  }

  /** Rotates the secret and validity window for an eligible invitation. */
  public Invitation resend(final ObjectId invitationId) {
    final Invitation current = requireInvitation(invitationId);
    final Parent inviter = access.requirePrimary(current.familyId());
    final Family family = requireFamily(current.familyId());
    if (!List.of(PENDING, "expired").contains(current.status())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Only pending or expired invitations can be resent");
    }
    final Instant now = Instant.now();
    final Invitation saved = invitations.save(new Invitation(current.id(), current.familyId(),
        current.email(), current.role(), PENDING, UUID.randomUUID().toString(), now,
        now.plus(7, ChronoUnit.DAYS), null, null, null, null, current.createdAt(), now));
    deliver(saved, inviter, family);
    audits.record(saved.familyId(), "invitation", saved.id(), "resend",
        Map.of("expiresAt", saved.expiresAt()));
    return saved;
  }

  /** Cancels a pending invitation. */
  public Invitation cancel(final ObjectId invitationId) {
    final Invitation current = requireInvitation(invitationId);
    access.requirePrimary(current.familyId());
    if (!PENDING.equals(current.status())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Only pending invitations can be canceled");
    }
    final Instant now = Instant.now();
    final Invitation saved = invitations.save(new Invitation(current.id(), current.familyId(),
        current.email(), current.role(), "canceled", current.token(), current.sentAt(),
        current.expiresAt(), null, now, null, null, current.createdAt(), now));
    audits.record(saved.familyId(), "invitation", saved.id(), "cancel", Map.of());
    return saved;
  }

  /** Atomically consumes a token, then idempotently links the intended authenticated parent. */
  public Acceptance accept(final String token) {
    if (token == null || token.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation token is required");
    }
    Invitation invitation = invitations.findByToken(token)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Invalid or expired invitation token"));
    if (!invitation.email().equalsIgnoreCase(identity.email())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "This invitation was sent to a different email address");
    }
    final Instant now = Instant.now();
    if (PENDING.equals(invitation.status()) && !invitation.expiresAt().isAfter(now)) {
      invitations.save(withStatus(invitation, "expired", now));
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation has expired");
    }
    if (PENDING.equals(invitation.status())) {
      invitation = mongoTemplate.findAndModify(
          Query.query(Criteria.where("_id").is(invitation.id())
              .and("status").is(PENDING).and("expiresAt").gt(now)),
          new Update().set("status", "accepted").set("acceptedAt", now)
              .set("acceptedByAuth0Id", identity.subject()).set("updatedAt", now),
          FindAndModifyOptions.options().returnNew(true), Invitation.class);
      if (invitation == null) {
        invitation = invitations.findByToken(token).orElseThrow();
      }
    }
    if (!"accepted".equals(invitation.status())
        || !identity.subject().equals(invitation.acceptedByAuth0Id())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Invitation has already been used or canceled");
    }

    final Family family = requireFamily(invitation.familyId());
    Parent parent = parents.findByFamilyIdAndAuth0IdAndStatus(
        family.id(), identity.subject(), CoparentAccessPolicy.ACTIVE).orElse(null);
    if (parent == null) {
      final Parent unassigned = parents.findFirstByAuth0IdAndFamilyIdIsNull(identity.subject())
          .orElse(null);
      parent = parents.save(new Parent(unassigned == null ? null : unassigned.id(),
          identity.subject(), family.id(), defaultName(unassigned), identity.email(),
          invitation.role(), CoparentAccessPolicy.ACTIVE,
          unassigned == null ? null : unassigned.color(),
          unassigned == null ? null : unassigned.avatarUrl(), now,
          unassigned == null ? now : unassigned.createdAt(), now));
    }
    if (!family.parentIds().contains(parent.id())) {
      final List<ObjectId> parentIds = new ArrayList<>(family.parentIds());
      parentIds.add(parent.id());
      families.save(new Family(family.id(), family.name(), family.timeZone(), parentIds,
          family.childIds(), family.invitationIds(), family.deletedAt(), family.createdAt(), now));
    }
    if (!parent.id().equals(invitation.acceptedParentId())) {
      invitation = invitations.save(new Invitation(invitation.id(), invitation.familyId(),
          invitation.email(), invitation.role(), invitation.status(), invitation.token(),
          invitation.sentAt(), invitation.expiresAt(), invitation.acceptedAt(), null,
          identity.subject(), parent.id(), invitation.createdAt(), now));
    }
    audits.record(family.id(), "invitation", invitation.id(), "accept",
        Map.of("parentId", parent.id().toHexString()));
    return new Acceptance(invitation, family);
  }

  private void deliver(
      final Invitation invitation,
      final Parent inviter,
      final Family family) {
    if (!mailer.send(invitation.email(), inviter.fullName(), family.name(), invitation.role(),
        invitation.token())) {
      log.warn("CoParent invitation {} stored but email delivery failed", invitation.id());
    }
  }

  private Invitation requireInvitation(final ObjectId invitationId) {
    final Invitation invitation = invitations.findById(invitationId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Invitation not found"));
    access.requireMember(invitation.familyId());
    return invitation;
  }

  private Family requireFamily(final ObjectId familyId) {
    return families.findByIdAndDeletedAtIsNull(familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Family not found"));
  }

  private Invitation expireIfRequired(final Invitation invitation) {
    if (PENDING.equals(invitation.status()) && !invitation.expiresAt().isAfter(Instant.now())) {
      return invitations.save(withStatus(invitation, "expired", Instant.now()));
    }
    return invitation;
  }

  private static Invitation withStatus(
      final Invitation current,
      final String status,
      final Instant now) {
    return new Invitation(current.id(), current.familyId(), current.email(), current.role(), status,
        current.token(), current.sentAt(), current.expiresAt(), current.acceptedAt(),
        current.canceledAt(), current.acceptedByAuth0Id(), current.acceptedParentId(),
        current.createdAt(), now);
  }

  private static String defaultName(final Parent unassigned) {
    if (unassigned != null && !unassigned.fullName().isBlank()) {
      return unassigned.fullName();
    }
    return "";
  }

  private static String normaliseEmail(final String email) {
    final String normalised = email == null ? "" : email.trim().toLowerCase();
    final int at = normalised.indexOf('@');
    final int dot = normalised.indexOf('.', at + 2);
    final boolean hasOneAt = at > 0 && at == normalised.lastIndexOf('@');
    final boolean hasDomain = dot > at + 1 && dot < normalised.length() - 1;
    final boolean hasWhitespace = normalised.chars().anyMatch(Character::isWhitespace);
    if (!hasOneAt || !hasDomain || hasWhitespace) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email address");
    }
    return normalised;
  }

  private static void validateRole(final String role) {
    if (!List.of("primary", "co-parent").contains(role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid parent role");
    }
  }

  /** Accepted invitation and the family now linked to the caller. */
  public record Acceptance(Invitation invitation, Family family) {
  }
}
