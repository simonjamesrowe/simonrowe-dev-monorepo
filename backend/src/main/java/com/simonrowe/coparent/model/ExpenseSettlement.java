package com.simonrowe.coparent.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One settling up: the money that changed hands, as one or more payments such as bank
 * transfers, and the expenses it paid back. The payments always add up to {@link #amountPence},
 * the net of those expenses, which moves from {@link #fromParentId} to {@link #toParentId}. Both
 * are null when the expenses cancelled each other out and nothing changed hands.
 *
 * <p>Never edited once recorded. Each expense it covers carries its id in
 * {@code reimbursement.settlementId}, and an expense being paid back can no longer change, so the
 * two cannot drift apart.
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
    Instant createdAt
) {
  public static final String COLLECTION = "expense_settlements";

  public static final String BANK_TRANSFER = "bank_transfer";
  public static final String CASH = "cash";
  public static final String OTHER = "other";

  public ExpenseSettlement {
    payments = payments == null ? List.of() : List.copyOf(payments);
    expenseIds = expenseIds == null ? List.of() : List.copyOf(expenseIds);
  }

  /** One transfer of money. {@code reference} is what the bank statement shows, if anything. */
  public record Payment(String method, long amountPence, LocalDate paidOn, String reference) {
  }
}
