package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.ExpenseRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Receipts attached to expenses: JPEG, PNG or PDF, at most five per expense, private to the
 * family. The type is read from the file's own first bytes, never from the name or the
 * {@code Content-Type} the browser claims, so a renamed file cannot pass as a receipt.
 *
 * <p>Adding or removing a receipt bumps the expense's {@code version}. It does not change the
 * terms, but every other write replaces the whole document guarded on that version, so a write
 * that did not bump it would be silently overwritten by an agree or a settle that read the
 * expense a moment earlier. The cost is that the other parent occasionally reloads before
 * agreeing.
 */
@Service
public class ExpenseReceiptService {

  static final int MAX_RECEIPTS = 5;
  private static final int MAX_DISPLAY_NAME = 100;

  private final ExpenseRepository expenses;
  private final CoparentReceiptStore store;
  private final CoparentAccessPolicy access;
  private final CoparentAuditService audits;
  private final MongoTemplate mongoTemplate;

  /**
   * A receipt's record and its bytes. Equality reads the bytes rather than the array reference,
   * and {@code toString} gives only their length, so a receipt photo never reaches a log line.
   */
  public record ReceiptFile(Expense.Receipt receipt, int position, byte[] bytes) {

    @Override
    public boolean equals(final Object other) {
      return other instanceof ReceiptFile that
          && position == that.position
          && Objects.equals(receipt, that.receipt)
          && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(receipt, position) + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
      return "ReceiptFile[receipt=%s, position=%d, bytes=<%d bytes>]"
          .formatted(receipt, position, bytes == null ? 0 : bytes.length);
    }
  }

  public ExpenseReceiptService(
      final ExpenseRepository expenses,
      final CoparentReceiptStore store,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this.expenses = expenses;
    this.store = store;
    this.access = access;
    this.audits = audits;
    this.mongoTemplate = mongoTemplate;
  }

  /** Attaches a receipt. Either parent may, at any point in the expense's life. */
  public Expense add(
      final ObjectId familyId,
      final ObjectId expenseId,
      final String originalName,
      final byte[] bytes) {
    final Parent actor = access.requireMember(familyId);
    find(familyId, expenseId);
    if (bytes == null || bytes.length == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The receipt file is empty");
    }
    final String contentType = sniff(bytes);
    if (contentType == null) {
      throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "Receipts must be a JPEG, PNG or PDF");
    }
    final Instant now = Instant.now();
    final Expense.Receipt receipt = new Expense.Receipt(new ObjectId(), contentType,
        bytes.length, displayName(originalName), actor.id(), now);
    // Bytes first: a record pointing at a missing file would show a broken receipt to both
    // parents, while a file with no record is simply never reached.
    store.store(familyId, receipt.id(), bytes);
    final Expense saved = mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(expenseId).and("familyId").is(familyId)
            .and("deletedAt").is(null)
            .and("receipts." + (MAX_RECEIPTS - 1)).exists(false)),
        history(new Update().push("receipts", receipt), actor, now, "receipt_add"),
        FindAndModifyOptions.options().returnNew(true), Expense.class);
    if (saved == null) {
      store.delete(familyId, receipt.id());
      final Expense current = find(familyId, expenseId);
      if (current.receipts().size() >= MAX_RECEIPTS) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "An expense can hold up to " + MAX_RECEIPTS + " receipts");
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This expense has changed. Reload it and try again.");
    }
    audits.record(familyId, "expense", expenseId, "receipt_add",
        Map.of("contentType", contentType, "sizeBytes", bytes.length));
    return saved;
  }

  /** Removes a receipt. Only the parent who added it, and not once the expense is settled. */
  public Expense remove(
      final ObjectId familyId,
      final ObjectId expenseId,
      final ObjectId receiptId) {
    final Parent actor = access.requireMember(familyId);
    final Expense current = find(familyId, expenseId);
    final Expense.Receipt receipt = current.receipts().stream()
        .filter(candidate -> candidate.id().equals(receiptId)).findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Receipt not found"));
    if (!actor.id().equals(receipt.uploadedBy())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Only the parent who added a receipt can remove it");
    }
    if (Expense.REIMBURSED.equals(current.reimbursement().status())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A settled expense keeps its receipts");
    }
    final Instant now = Instant.now();
    final Expense saved = mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(expenseId).and("familyId").is(familyId)
            .and("deletedAt").is(null).and("receipts._id").is(receiptId)),
        history(new Update().pull("receipts", new Document("_id", receiptId)), actor, now,
            "receipt_remove"),
        FindAndModifyOptions.options().returnNew(true), Expense.class);
    if (saved == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
    }
    store.delete(familyId, receiptId);
    audits.record(familyId, "expense", expenseId, "receipt_remove", Map.of());
    return saved;
  }

  /** Reads a receipt for a member of the family. Anyone else gets 404. */
  public ReceiptFile read(
      final ObjectId familyId,
      final ObjectId expenseId,
      final ObjectId receiptId) {
    access.requireMember(familyId);
    final Expense expense = find(familyId, expenseId);
    for (int index = 0; index < expense.receipts().size(); index++) {
      final Expense.Receipt receipt = expense.receipts().get(index);
      if (receipt.id().equals(receiptId)) {
        final int position = index + 1;
        return store.read(familyId, receiptId)
            .map(bytes -> new ReceiptFile(receipt, position, bytes))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Receipt not found"));
      }
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
  }

  /** The content type a file's leading bytes prove it to be, or null for anything else. */
  static String sniff(final byte[] bytes) {
    if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
      return "image/jpeg";
    }
    if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
      return "image/png";
    }
    if (startsWith(bytes, '%', 'P', 'D', 'F', '-')) {
      return "application/pdf";
    }
    return null;
  }

  /** A name to show beside the thumbnail. Never used as a path, but still kept plain. */
  static String displayName(final String originalName) {
    if (originalName == null) {
      return "receipt";
    }
    final String base = originalName.replace('\\', '/');
    final String file = base.substring(base.lastIndexOf('/') + 1)
        .replaceAll("[^A-Za-z0-9 ._-]", "").trim();
    if (file.isEmpty()) {
      return "receipt";
    }
    return file.length() > MAX_DISPLAY_NAME ? file.substring(0, MAX_DISPLAY_NAME) : file;
  }

  /** The extension a downloaded receipt is named with. */
  static String extension(final String contentType) {
    return switch (contentType.toLowerCase(Locale.ROOT)) {
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      default -> "pdf";
    };
  }

  private static boolean startsWith(final byte[] bytes, final int... prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int index = 0; index < prefix.length; index++) {
      if ((bytes[index] & 0xFF) != prefix[index]) {
        return false;
      }
    }
    return true;
  }

  private static Update history(final Update update, final Parent actor, final Instant now,
      final String action) {
    return update
        .push("history").slice(-ExpenseService.MAX_HISTORY)
        .each(new Expense.HistoryEntry(now, actor.id(), action, null))
        .inc("version", 1)
        .set("updatedAt", now);
  }

  private Expense find(final ObjectId familyId, final ObjectId expenseId) {
    return expenses.findByIdAndFamilyIdAndDeletedAtIsNull(expenseId, familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Expense not found"));
  }
}
