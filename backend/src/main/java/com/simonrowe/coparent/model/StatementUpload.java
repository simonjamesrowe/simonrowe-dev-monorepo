package com.simonrowe.coparent.model;

import java.time.Instant;
import java.time.LocalDate;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What one statement upload held and how far checking it got. Counts and dates only: no
 * transaction, file name or file is kept.
 */
@Document(StatementUpload.COLLECTION)
public record StatementUpload(
    @Id ObjectId id,
    ObjectId familyId,
    ObjectId ownerParentId,
    String format,
    String account,
    LocalDate fromDate,
    LocalDate toDate,
    int spendingCount,
    int moneyInCount,
    int unreadableCount,
    int newCount,
    int checkedCount,
    int suggestedCount,
    boolean aiEnabled,
    Instant createdAt,
    Instant updatedAt
) {
  public static final String COLLECTION = "statement_uploads";
}
