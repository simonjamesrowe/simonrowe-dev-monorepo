package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.AuditRecord;
import com.simonrowe.coparent.shared.CoparentIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Writes deliberately redacted, append-only mutation evidence. */
@Service
public class CoparentAuditService {

  private final AuditRepository audits;
  private final CoparentIdentity identity;
  private final Clock clock;

  @Autowired
  public CoparentAuditService(
      final AuditRepository audits,
      final CoparentIdentity identity) {
    this(audits, identity, Clock.systemUTC());
  }

  CoparentAuditService(
      final AuditRepository audits,
      final CoparentIdentity identity,
      final Clock clock) {
    this.audits = audits;
    this.identity = identity;
    this.clock = clock;
  }

  /** Records a mutation without storing message bodies, medical notes, or tokens. */
  public void record(
      final ObjectId familyId,
      final String entityType,
      final ObjectId entityId,
      final String action,
      final Map<String, Object> safeChanges) {
    final Instant now = clock.instant();
    audits.save(new AuditRecord(null, familyId, entityType, entityId.toHexString(), action,
        identity.subject(), safeChanges, now, now, now, null));
  }

  /** Writes the single redacted receipt for an assistant action, safely across retries. */
  public void recordAssistantReceipt(
      final ObjectId familyId,
      final ObjectId assistantActionId,
      final Map<String, Object> safeChanges) {
    if (audits.existsByAssistantActionId(assistantActionId)) {
      return;
    }
    final Instant now = clock.instant();
    try {
      audits.save(new AuditRecord(null, familyId, "assistant_action",
          assistantActionId.toHexString(), "apply", identity.subject(), safeChanges,
          now, now, now, assistantActionId));
    } catch (DuplicateKeyException ignored) {
      // A retry reconciled the same committed action; the first receipt is authoritative.
    }
  }
}
