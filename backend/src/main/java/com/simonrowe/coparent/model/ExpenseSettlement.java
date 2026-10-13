package com.simonrowe.coparent.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One settling up: the money that changed hands, as one or more payments such as bank
 * transfers, and the expenses it paid back. {@link #amountPence} is the net of those expenses,
 * owed by {@link #fromParentId} to {@link #toParentId}. Both are null when the expenses cancelled
 * each other out and nothing changed hands.
 *
 * <p>The payments are what actually moved, and need not match the expenses exactly: a round
 * £1,500 sent against £1,478.68 of expenses is recorded as sent. The {@link #differencePence}
 * is carried into the balance, so the expenses are settled and the £21.32 is owed back.
 *
 * <p>Only the payments can be edited, by the parent who recorded them. The expenses it covers
 * carry its id in {@code reimbursement.settlementId}, and an expense being paid back can no
 * longer change, so the two cannot drift apart.
 */
@Document(ExpenseSettlement.COLLECTION)
public record ExpenseSettlement(
    @Id ObjectId id,
    ObjectId familyId,
    ObjectId fromParentId,
    ObjectId toParentId,
    long amountPence,
    String currency,
    List<Payment> payments,
    List<ObjectId> expenseIds,
    ObjectId recordedBy,
    Instant createdAt,
    ObjectId updatedBy,
    Instant updatedAt
) {
  public static final String COLLECTION = "expense_settlements";

  public static final String BANK_TRANSFER = "bank_transfer";
  public static final String CASH = "cash";
  public static final String OTHER = "other";

  public ExpenseSettlement {
    payments = payments == null ? List.of() : List.copyOf(payments);
    expenseIds = expenseIds == null ? List.of() : List.copyOf(expenseIds);
  }

  /** What the payments add up to: the money that actually changed hands. */
  public long paidPence() {
    return payments.stream().mapToLong(Payment::amountPence).sum();
  }

  /**
   * How much more than the expenses came to was paid, from {@link #fromParentId} to
   * {@link #toParentId}. Positive is an overpayment the other parent now owes back; negative is
   * a shortfall still owed.
   */
  public long differencePence() {
    return paidPence() - amountPence;
  }

  /** The same settle-up with its payments replaced. */
  public ExpenseSettlement withPayments(final List<Payment> replaced, final ObjectId by,
      final Instant at) {
    return new ExpenseSettlement(id, familyId, fromParentId, toParentId, amountPence, currency,
        replaced, expenseIds, recordedBy, createdAt, by, at);
  }

  /** One transfer of money. {@code reference} is what the bank statement shows, if anything. */
  public record Payment(String method, long amountPence, LocalDate paidOn, String reference) {
  }
}
