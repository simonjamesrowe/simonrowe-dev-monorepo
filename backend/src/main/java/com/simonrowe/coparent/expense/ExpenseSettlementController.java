package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.model.ExpenseSettlement;
import com.simonrowe.coparent.shared.CoparentIds;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HTTP contract for settling up with payments. Every amount is pence of pounds sterling. */
@RestController
@RequestMapping("/api/coparent/families/{familyId}/expenses/settlements")
public class ExpenseSettlementController {

  private final ExpenseSettlementService service;

  public ExpenseSettlementController(final ExpenseSettlementService service) {
    this.service = service;
  }

  @GetMapping
  List<SettlementResponse> list(@PathVariable final String familyId) {
    return service.list(CoparentIds.parse(familyId)).stream().map(SettlementResponse::from)
        .toList();
  }

  @PostMapping
  RecordedResponse record(
      @PathVariable final String familyId,
      @RequestBody final RecordRequest request) {
    final List<ExpenseService.SettleItem> items = request.items() == null ? List.of()
        : request.items().stream().map(item -> new ExpenseService.SettleItem(
            CoparentIds.parse(item.id()), requireVersion(item.version()))).toList();
    final ExpenseSettlementService.Recorded recorded =
        service.record(CoparentIds.parse(familyId), items, values(request.payments()));
    return new RecordedResponse(
        recorded.settlement() == null ? null : SettlementResponse.from(recorded.settlement()),
        recorded.results().stream().map(ExpenseController.SettleResponse::from).toList());
  }

  /** Corrects what was paid, and when and how. The expenses it paid back are unchanged. */
  @PutMapping("/{settlementId}")
  SettlementResponse update(
      @PathVariable final String familyId,
      @PathVariable final String settlementId,
      @RequestBody final UpdateRequest request) {
    return SettlementResponse.from(service.update(CoparentIds.parse(familyId),
        CoparentIds.parse(settlementId), values(request.payments())));
  }

  @PostMapping("/{settlementId}/confirm")
  List<ExpenseController.SettleResponse> confirm(
      @PathVariable final String familyId,
      @PathVariable final String settlementId) {
    return service.confirm(CoparentIds.parse(familyId), CoparentIds.parse(settlementId)).stream()
        .map(ExpenseController.SettleResponse::from).toList();
  }

  private static List<ExpenseSettlementService.PaymentValues> values(
      final List<PaymentRequest> payments) {
    return payments == null ? List.of() : payments.stream().map(PaymentRequest::values).toList();
  }

  private static long requireVersion(final Long version) {
    if (version == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The version of each expense you are settling is required");
    }
    return version;
  }

  record RecordRequest(List<ItemRequest> items, List<PaymentRequest> payments) {
  }

  record ItemRequest(String id, Long version) {
  }

  record UpdateRequest(List<PaymentRequest> payments) {
  }

  record PaymentRequest(String method, Long amountPence, LocalDate paidOn, String reference) {
    ExpenseSettlementService.PaymentValues values() {
      return new ExpenseSettlementService.PaymentValues(method,
          amountPence == null ? 0 : amountPence, paidOn, reference);
    }
  }

  record RecordedResponse(SettlementResponse settlement,
      List<ExpenseController.SettleResponse> results) {
  }

  record PaymentResponse(String method, long amountPence, LocalDate paidOn, String reference) {
  }

  record SettlementResponse(
      String id,
      String familyId,
      String fromParentId,
      String toParentId,
      long amountPence,
      long paidPence,
      String currency,
      List<PaymentResponse> payments,
      List<String> expenseIds,
      String recordedBy,
      Instant createdAt,
      String updatedBy,
      Instant updatedAt
  ) {
    static SettlementResponse from(final ExpenseSettlement settlement) {
      return new SettlementResponse(settlement.id().toHexString(),
          settlement.familyId().toHexString(), hex(settlement.fromParentId()),
          hex(settlement.toParentId()), settlement.amountPence(), settlement.paidPence(),
          settlement.currency(),
          settlement.payments().stream().map(payment -> new PaymentResponse(payment.method(),
              payment.amountPence(), payment.paidOn(), payment.reference())).toList(),
          settlement.expenseIds().stream().map(ObjectId::toHexString).toList(),
          hex(settlement.recordedBy()), settlement.createdAt(), hex(settlement.updatedBy()),
          settlement.updatedAt());
    }
  }

  private static String hex(final ObjectId id) {
    return id == null ? null : id.toHexString();
  }
}
