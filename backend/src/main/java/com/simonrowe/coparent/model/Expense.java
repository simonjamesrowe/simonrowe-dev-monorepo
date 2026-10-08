package com.simonrowe.coparent.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A cost shared between the two parents of a family, in pence of pounds sterling.
 *
 * <p>Who paid ({@link #payerId}) and how the cost is shared ({@link #shares}) are separate. The
 * parent who last changed the terms (amount, share, payer) has agreed to them; the other parent
 * must agree before the expense counts towards the balance. {@link #version} increases on every
 * write and every change is guarded on it, so nobody can agree to an amount that has since moved.
 */
@Document(Expense.COLLECTION)
public record Expense(
    @Id ObjectId id,
    ObjectId familyId,
    String title,
    String category,
    List<ObjectId> childIds,
    long amountPence,
    String currency,
    String timing,
    LocalDate date,
    ObjectId payerId,
    List<Share> shares,
    Agreement agreement,
    Reimbursement reimbursement,
    List<Receipt> receipts,
    String notes,
    List<HistoryEntry> history,
    long version,
    ObjectId createdBy,
    ObjectId assistantActionId,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt
) {
  public static final String COLLECTION = "expenses";
  public static final String GBP = "GBP";

  public static final String PAID = "paid";
  public static final String UPCOMING = "upcoming";

  public static final String PENDING = "pending";
  public static final String AGREED = "agreed";
  public static final String DISPUTED = "disputed";

  public static final String NONE = "none";
  public static final String OUTSTANDING = "outstanding";
  public static final String CLAIMED = "claimed";
  public static final String REIMBURSED = "reimbursed";

  public Expense {
    childIds = childIds == null ? List.of() : List.copyOf(childIds);
    shares = shares == null ? List.of() : List.copyOf(shares);
    receipts = receipts == null ? List.of() : List.copyOf(receipts);
    history = history == null ? List.of() : List.copyOf(history);
  }

  /** One parent's percentage of the cost. The two percentages always sum to 100. */
  public record Share(ObjectId parentId, int percent) {
  }

  /** Whether the other parent has accepted the current terms. */
  public record Agreement(
      String status,
      ObjectId requestedBy,
      ObjectId respondedBy,
      Instant respondedAt,
      String note
  ) {
  }

  /** Whether the parent who owes has paid the parent who paid. */
  public record Reimbursement(
      String status,
      ObjectId claimedBy,
      Instant claimedAt,
      ObjectId settledBy,
      Instant settledAt,
      String note
  ) {
    public static Reimbursement none() {
      return new Reimbursement(NONE, null, null, null, null, null);
    }
  }

  /** Metadata for a privately stored receipt file. The bytes never live in Mongo. */
  public record Receipt(
      ObjectId id,
      String contentType,
      long sizeBytes,
      String displayName,
      ObjectId uploadedBy,
      Instant uploadedAt
  ) {
  }

  /** One line of the expense's own timeline, shown to both parents. */
  public record HistoryEntry(Instant at, ObjectId by, String action, String note) {
  }
}
