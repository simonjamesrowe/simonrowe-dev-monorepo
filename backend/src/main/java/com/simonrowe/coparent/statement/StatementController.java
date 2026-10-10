package com.simonrowe.coparent.statement;

import com.simonrowe.coparent.expense.ExpenseController;
import com.simonrowe.coparent.model.StatementTransaction;
import com.simonrowe.coparent.model.StatementUpload;
import com.simonrowe.coparent.shared.CoparentIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Statement uploads and shared-cost suggestions. Every call is private to the caller. */
@RestController
@RequestMapping("/api/coparent/families/{familyId}/statements")
public class StatementController {

  private final StatementService service;

  public StatementController(final StatementService service) {
    this.service = service;
  }

  @GetMapping
  OverviewResponse overview(@PathVariable final String familyId) {
    return OverviewResponse.from(service.overview(CoparentIds.parse(familyId)));
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  UploadResponse upload(
      @PathVariable final String familyId,
      @RequestParam("file") final MultipartFile file) {
    final byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file could not be read");
    }
    return UploadResponse.from(service.upload(CoparentIds.parse(familyId), bytes));
  }

  @PostMapping("/uploads/{uploadId}/check")
  List<CheckResponse> check(
      @PathVariable final String familyId,
      @PathVariable final String uploadId,
      @Valid @RequestBody final CheckRequest request) {
    return service.check(CoparentIds.parse(familyId), CoparentIds.parse(uploadId),
        request.rows().stream().map(RowRequest::input).toList()).stream()
        .map(CheckResponse::from).toList();
  }

  @GetMapping("/transactions")
  List<TransactionResponse> list(
      @PathVariable final String familyId,
      @RequestParam(defaultValue = StatementTransaction.SUGGESTED) final String status) {
    return service.list(CoparentIds.parse(familyId), status).stream()
        .map(TransactionResponse::from).toList();
  }

  @PostMapping("/transactions/{transactionId}/dismiss")
  TransactionResponse dismiss(
      @PathVariable final String familyId,
      @PathVariable final String transactionId) {
    return TransactionResponse.from(new StatementService.Listed(service.dismiss(
        CoparentIds.parse(familyId), CoparentIds.parse(transactionId)), null, null));
  }

  @DeleteMapping("/transactions/{transactionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void forget(
      @PathVariable final String familyId,
      @PathVariable final String transactionId) {
    service.forget(CoparentIds.parse(familyId), CoparentIds.parse(transactionId));
  }

  @PostMapping("/expenses")
  @ResponseStatus(HttpStatus.CREATED)
  ExpenseController.ExpenseResponse convert(
      @PathVariable final String familyId,
      @Valid @RequestBody final ConvertRequest request) {
    return ExpenseController.ExpenseResponse.from(service.convert(CoparentIds.parse(familyId),
        request.fingerprint(),
        request.uploadId() == null || request.uploadId().isBlank() ? null
            : CoparentIds.parse(request.uploadId()),
        request.transaction() == null ? null : request.transaction().input(),
        request.expense().values()));
  }

  // ---- requests ----------------------------------------------------------------------------

  record RowRequest(
      String fingerprint,
      LocalDate date,
      String description,
      String details,
      Long amountPence,
      String account
  ) {
    StatementService.RowInput input() {
      return new StatementService.RowInput(fingerprint, date, description, details,
          amountPence == null ? 0 : amountPence, account);
    }
  }

  record CheckRequest(@NotNull @Size(min = 1, max = StatementClassifier.MAX_BATCH)
      List<RowRequest> rows) {
  }

  record ConvertRequest(
      String fingerprint,
      String uploadId,
      RowRequest transaction,
      @NotNull @Valid ExpenseController.ExpenseRequest expense
  ) {
  }

  // ---- responses ---------------------------------------------------------------------------

  record MatchResponse(String id, String title, LocalDate date) {
    static MatchResponse from(final StatementService.Match match) {
      return match == null ? null
          : new MatchResponse(match.id().toHexString(), match.title(), match.date());
    }
  }

  record SuggestionResponse(
      String confidence, String title, String category, List<String> childIds, String reason) {
    static SuggestionResponse from(final StatementTransaction.Suggestion suggestion) {
      return suggestion == null ? null : new SuggestionResponse(suggestion.confidence(),
          suggestion.title(), suggestion.category(),
          suggestion.childIds().stream().map(ObjectId::toHexString).toList(),
          suggestion.reason());
    }
  }

  record RowResponse(
      String fingerprint,
      LocalDate date,
      String description,
      String details,
      long amountPence,
      String account,
      String state,
      String transactionId,
      String expenseId,
      MatchResponse match
  ) {
    static RowResponse from(final StatementService.Row row) {
      return new RowResponse(row.fingerprint(), row.date(), row.description(), row.details(),
          row.amountPence(), row.account(), row.state(), hex(row.transactionId()),
          hex(row.expenseId()), MatchResponse.from(row.match()));
    }
  }

  record UploadResponse(
      UploadSummary upload,
      String format,
      String account,
      LocalDate from,
      LocalDate to,
      int moneyIn,
      int unreadable,
      List<RowResponse> rows,
      boolean aiEnabled
  ) {
    static UploadResponse from(final StatementService.UploadResult result) {
      return new UploadResponse(result.upload() == null ? null
          : UploadSummary.from(result.upload(), result.upload().aiEnabled() ? "checking" : "off"),
          result.format(), result.account(), result.from(), result.to(), result.moneyIn(),
          result.unreadable(), result.rows().stream().map(RowResponse::from).toList(),
          result.aiEnabled());
    }
  }

  record UploadSummary(
      String id,
      String format,
      String account,
      LocalDate from,
      LocalDate to,
      int spendingCount,
      int moneyInCount,
      int newCount,
      int checkedCount,
      int suggestedCount,
      String status,
      Instant createdAt
  ) {
    static UploadSummary from(final StatementUpload upload, final String status) {
      return new UploadSummary(upload.id().toHexString(), upload.format(), upload.account(),
          upload.fromDate(), upload.toDate(), upload.spendingCount(), upload.moneyInCount(),
          upload.newCount(), Math.min(upload.checkedCount(), upload.newCount()),
          upload.suggestedCount(), status, upload.createdAt());
    }
  }

  record OverviewResponse(
      boolean aiEnabled, long toReview, long dismissed, long logged,
      List<UploadSummary> uploads) {
    static OverviewResponse from(final StatementService.Overview overview) {
      return new OverviewResponse(overview.aiEnabled(), overview.toReview(),
          overview.dismissed(), overview.logged(), overview.uploads().stream()
              .map(view -> UploadSummary.from(view.upload(), view.status())).toList());
    }
  }

  record TransactionResponse(
      String id,
      String fingerprint,
      String status,
      LocalDate date,
      String description,
      String details,
      Long amountPence,
      String account,
      SuggestionResponse suggestion,
      String merchant,
      String expenseId,
      String expenseTitle,
      String uploadId,
      MatchResponse match,
      Instant decidedAt
  ) {
    static TransactionResponse from(final StatementService.Listed listed) {
      final StatementTransaction transaction = listed.transaction();
      final StatementTransaction.Pending pending = transaction.pending();
      return new TransactionResponse(transaction.id().toHexString(), transaction.fingerprint(),
          transaction.status(),
          pending == null ? null : pending.date(),
          pending == null ? null : pending.description(),
          pending == null ? null : pending.details(),
          pending == null ? null : pending.amountPence(),
          pending == null ? null : pending.account(),
          pending == null ? null : SuggestionResponse.from(pending.suggestion()),
          transaction.merchant(), hex(transaction.expenseId()), listed.expenseTitle(),
          hex(transaction.uploadId()), MatchResponse.from(listed.match()),
          transaction.decidedAt());
    }
  }

  record CheckResponse(String fingerprint, String state, TransactionResponse transaction) {
    static CheckResponse from(final StatementService.CheckResult result) {
      return new CheckResponse(result.fingerprint(), result.state(),
          result.transaction() == null ? null : TransactionResponse.from(
              new StatementService.Listed(result.transaction(), null, null)));
    }
  }

  private static String hex(final ObjectId id) {
    return id == null ? null : id.toHexString();
  }
}
