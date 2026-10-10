package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.shared.CoparentIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HTTP contract for shared expenses. Every amount is pence of pounds sterling. */
@RestController
@RequestMapping("/api/coparent/families/{familyId}/expenses")
public class ExpenseController {

  private final ExpenseService service;

  public ExpenseController(final ExpenseService service) {
    this.service = service;
  }

  @GetMapping
  List<ExpenseResponse> list(@PathVariable final String familyId) {
    return service.list(CoparentIds.parse(familyId)).stream().map(ExpenseResponse::from).toList();
  }

  @GetMapping("/summary")
  SummaryResponse summary(@PathVariable final String familyId) {
    return SummaryResponse.from(service.summary(CoparentIds.parse(familyId)));
  }

  @GetMapping("/{expenseId}")
  ExpenseResponse get(@PathVariable final String familyId, @PathVariable final String expenseId) {
    return ExpenseResponse.from(service.get(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId)));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ExpenseResponse create(
      @PathVariable final String familyId,
      @Valid @RequestBody final ExpenseRequest request) {
    return ExpenseResponse.from(service.create(CoparentIds.parse(familyId), request.values()));
  }

  @PutMapping("/{expenseId}")
  ExpenseResponse update(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @Valid @RequestBody final ExpenseRequest request) {
    return ExpenseResponse.from(service.update(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), requireVersion(request.version()), request.values()));
  }

  @PostMapping("/{expenseId}/mark-paid")
  ExpenseResponse markPaid(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @Valid @RequestBody final MarkPaidRequest request) {
    return ExpenseResponse.from(service.markPaid(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), requireVersion(request.version()),
        CoparentIds.parse(request.payerId()), request.paidOn(), request.amountPence()));
  }

  @PostMapping("/{expenseId}/agree")
  ExpenseResponse agree(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @RequestBody final VersionRequest request) {
    return ExpenseResponse.from(service.agree(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), requireVersion(request.version())));
  }

  @PostMapping("/{expenseId}/dispute")
  ExpenseResponse dispute(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @RequestBody final NoteRequest request) {
    return ExpenseResponse.from(service.dispute(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), requireVersion(request.version()), request.note()));
  }

  @PostMapping("/{expenseId}/reimbursement/claim")
  ExpenseResponse claim(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @RequestBody final NoteRequest request) {
    return ExpenseResponse.from(service.claim(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), requireVersion(request.version()), request.note()));
  }

  @PostMapping("/{expenseId}/reimbursement/confirm")
  ExpenseResponse confirm(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @RequestBody final VersionRequest request) {
    return ExpenseResponse.from(service.confirm(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), requireVersion(request.version())));
  }

  @PostMapping("/{expenseId}/reimbursement/reject")
  ExpenseResponse reject(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @RequestBody final NoteRequest request) {
    return ExpenseResponse.from(service.reject(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), requireVersion(request.version()), request.note()));
  }

  @PostMapping("/{expenseId}/reimbursement/mark")
  ExpenseResponse markReimbursed(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @RequestBody final VersionRequest request) {
    return ExpenseResponse.from(service.markReimbursed(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), requireVersion(request.version())));
  }

  @PostMapping("/settle")
  List<SettleResponse> settle(
      @PathVariable final String familyId,
      @RequestBody final SettleRequest request) {
    final List<ExpenseService.SettleItem> items = request.items() == null ? List.of()
        : request.items().stream().map(item -> new ExpenseService.SettleItem(
            CoparentIds.parse(item.id()), requireVersion(item.version()))).toList();
    return service.settle(CoparentIds.parse(familyId), items).stream()
        .map(SettleResponse::from).toList();
  }

  @DeleteMapping("/{expenseId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @RequestParam final Long version) {
    service.delete(CoparentIds.parse(familyId), CoparentIds.parse(expenseId),
        requireVersion(version));
  }

  private static long requireVersion(final Long version) {
    if (version == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The version of the expense you are changing is required");
    }
    return version;
  }

  // ---- requests ----------------------------------------------------------------------------

  /** The body of a create or full update, also accepted when a statement row becomes one. */
  public record ExpenseRequest(
      @NotBlank @Size(max = ExpenseService.MAX_TITLE) String title,
      @NotBlank String category,
      @NotEmpty List<String> childIds,
      @NotNull @Positive @Max(ExpenseService.MAX_AMOUNT_PENCE) Long amountPence,
      // Optional, and only GBP: a request naming any other currency is refused, never stored.
      String currency,
      @NotBlank String timing,
      @NotNull LocalDate date,
      String payerId,
      @NotNull @Size(min = 2, max = 2) List<ShareRequest> shares,
      @Size(max = ExpenseService.MAX_NOTES) String notes,
      Long version
  ) {
    public ExpenseService.ExpenseValues values() {
      if (currency != null && !Expense.GBP.equals(currency)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Expenses are in pounds sterling (GBP) only");
      }
      return new ExpenseService.ExpenseValues(title, category,
          childIds.stream().map(CoparentIds::parse).toList(), amountPence, timing, date,
          payerId == null || payerId.isBlank() ? null : CoparentIds.parse(payerId),
          shares.stream().map(ShareRequest::share).toList(), notes);
    }
  }

  public record ShareRequest(String parentId, Integer percent) {
    Expense.Share share() {
      if (percent == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Each parent's share needs a percentage");
      }
      return new Expense.Share(CoparentIds.parse(parentId), percent);
    }
  }

  record MarkPaidRequest(
      @NotBlank String payerId,
      @NotNull LocalDate paidOn,
      @NotNull @Positive @Max(ExpenseService.MAX_AMOUNT_PENCE) Long amountPence,
      Long version
  ) {
  }

  record VersionRequest(Long version) {
  }

  record NoteRequest(String note, Long version) {
  }

  record SettleRequest(List<SettleItemRequest> items) {
  }

  record SettleItemRequest(String id, Long version) {
  }

  // ---- responses ---------------------------------------------------------------------------

  public record ExpenseResponse(
      String id,
      String familyId,
      String title,
      String category,
      List<String> childIds,
      long amountPence,
      String currency,
      String timing,
      LocalDate date,
      String payerId,
      List<ShareResponse> shares,
      AgreementResponse agreement,
      ReimbursementResponse reimbursement,
      long owedPence,
      String debtorParentId,
      String creditorParentId,
      boolean countsTowardsBalance,
      List<ReceiptResponse> receipts,
      String notes,
      List<HistoryResponse> history,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt
  ) {
    public static ExpenseResponse from(final Expense expense) {
      final ObjectId debtor = ExpenseMath.debtor(expense);
      return new ExpenseResponse(expense.id().toHexString(), expense.familyId().toHexString(),
          expense.title(), expense.category(),
          expense.childIds().stream().map(ObjectId::toHexString).toList(),
          expense.amountPence(), expense.currency(), expense.timing(), expense.date(),
          hex(expense.payerId()),
          expense.shares().stream().map(share -> new ShareResponse(
              share.parentId().toHexString(), share.percent(),
              ExpenseMath.shareOf(expense, share.parentId()))).toList(),
          AgreementResponse.from(expense.agreement()),
          ReimbursementResponse.from(expense.reimbursement()),
          ExpenseMath.owedPence(expense), hex(debtor), hex(expense.payerId()),
          ExpenseMath.countsTowardsBalance(expense),
          expense.receipts().stream().map(ReceiptResponse::from).toList(),
          expense.notes(),
          expense.history().stream().map(HistoryResponse::from).toList(),
          expense.version(), hex(expense.createdBy()), expense.createdAt(),
          expense.updatedAt());
    }
  }

  public record ShareResponse(String parentId, int percent, long sharePence) {
  }

  public record AgreementResponse(
      String status, String requestedBy, String respondedBy, Instant respondedAt, String note) {
    static AgreementResponse from(final Expense.Agreement agreement) {
      return new AgreementResponse(agreement.status(), hex(agreement.requestedBy()),
          hex(agreement.respondedBy()), agreement.respondedAt(), agreement.note());
    }
  }

  public record ReimbursementResponse(
      String status, String claimedBy, Instant claimedAt, String settledBy, Instant settledAt,
      String note) {
    static ReimbursementResponse from(final Expense.Reimbursement reimbursement) {
      return new ReimbursementResponse(reimbursement.status(), hex(reimbursement.claimedBy()),
          reimbursement.claimedAt(), hex(reimbursement.settledBy()), reimbursement.settledAt(),
          reimbursement.note());
    }
  }

  public record ReceiptResponse(
      String id, String contentType, long sizeBytes, String displayName, String uploadedBy,
      Instant uploadedAt) {
    static ReceiptResponse from(final Expense.Receipt receipt) {
      return new ReceiptResponse(receipt.id().toHexString(), receipt.contentType(),
          receipt.sizeBytes(), receipt.displayName(), hex(receipt.uploadedBy()),
          receipt.uploadedAt());
    }
  }

  public record HistoryResponse(Instant at, String by, String action, String note) {
    static HistoryResponse from(final Expense.HistoryEntry entry) {
      return new HistoryResponse(entry.at(), hex(entry.by()), entry.action(), entry.note());
    }
  }

  record SettleResponse(String id, String outcome, ExpenseResponse expense, String message) {
    static SettleResponse from(final ExpenseService.SettleResult result) {
      return new SettleResponse(result.id().toHexString(), result.outcome(),
          result.expense() == null ? null : ExpenseResponse.from(result.expense()),
          result.message());
    }
  }

  record SummaryResponse(
      String currency,
      BalanceResponse balance,
      ExpenseSummary.Total needsYourAgreement,
      int needsYourAction,
      int awaitingOther,
      UpcomingResponse upcoming,
      ExpenseSummary.ThisMonth thisMonth
  ) {
    static SummaryResponse from(final ExpenseSummary summary) {
      final ExpenseSummary.Balance balance = summary.balance();
      return new SummaryResponse(summary.currency(),
          new BalanceResponse(balance.netPence(), hex(balance.debtorParentId()),
              hex(balance.creditorParentId()), balance.expenseCount(),
              balance.awaitingYourConfirmationPence(), balance.awaitingTheirConfirmationPence()),
          summary.needsYourAgreement(), summary.needsYourAction(), summary.awaitingOther(),
          new UpcomingResponse(summary.upcoming().count(), summary.upcoming().totalPence(),
              summary.upcoming().yourSharePence(),
              summary.upcoming().next().stream().map(ExpenseResponse::from).toList()),
          summary.thisMonth());
    }
  }

  record BalanceResponse(
      long netPence,
      String debtorParentId,
      String creditorParentId,
      int expenseCount,
      long awaitingYourConfirmationPence,
      long awaitingTheirConfirmationPence
  ) {
  }

  record UpcomingResponse(
      int count, long totalPence, long yourSharePence, List<ExpenseResponse> next) {
  }

  private static String hex(final ObjectId id) {
    return id == null ? null : id.toHexString();
  }
}
