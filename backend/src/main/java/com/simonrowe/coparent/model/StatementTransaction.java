package com.simonrowe.coparent.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One bank or card transaction a parent has uploaded, private to that parent and keyed on the
 * statement row's one-way {@link #fingerprint}, which is what makes a re-upload add nothing.
 *
 * <p>The statement itself is never kept. A transaction the model did not suggest is stored as
 * its fingerprint alone ({@link #CHECKED}). Only a suggestion still waiting for the parent holds
 * the row's details ({@link #pending}); deciding drops them, keeping only the merchant's name so
 * later suggestions can follow the parent's earlier choices. A logged transaction's facts live on
 * in the expense it became.
 */
@Document(StatementTransaction.COLLECTION)
public record StatementTransaction(
    @Id ObjectId id,
    ObjectId familyId,
    ObjectId ownerParentId,
    String fingerprint,
    String status,
    Pending pending,
    String merchant,
    String category,
    ObjectId expenseId,
    ObjectId uploadId,
    Instant decidedAt,
    Instant createdAt,
    Instant updatedAt
) {
  public static final String COLLECTION = "statement_transactions";

  /** Seen and not suggested. Nothing but the fingerprint is kept. */
  public static final String CHECKED = "checked";
  /** Suggested as a possible shared cost and waiting for the parent. */
  public static final String SUGGESTED = "suggested";
  /** The parent said it is not shared. */
  public static final String DISMISSED = "dismissed";
  /** Turned into an expense. */
  public static final String LOGGED = "logged";

  /** The row's details, kept only while a suggestion waits for the parent's decision. */
  public record Pending(
      LocalDate date,
      String description,
      String details,
      long amountPence,
      String account,
      Suggestion suggestion
  ) {
  }

  /** What the model suggested the expense should be. */
  public record Suggestion(
      String confidence,
      String title,
      String category,
      List<ObjectId> childIds,
      String reason
  ) {
    public Suggestion {
      childIds = childIds == null ? List.of() : List.copyOf(childIds);
    }
  }
}
