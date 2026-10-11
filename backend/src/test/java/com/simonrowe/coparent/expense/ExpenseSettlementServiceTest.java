package com.simonrowe.coparent.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.coparent.model.ExpenseSettlement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpenseSettlementServiceTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 8);

  @Test
  void eachSettledExpenseSaysHowItWasPaid() {
    assertThat(ExpenseSettlementService.methodNote(List.of())).isEqualTo(
        "Settled against each other");
    assertThat(ExpenseSettlementService.methodNote(List.of(payment(ExpenseSettlement.CASH))))
        .isEqualTo("Cash");
    assertThat(ExpenseSettlementService.methodNote(List.of(
        payment(ExpenseSettlement.BANK_TRANSFER), payment(ExpenseSettlement.BANK_TRANSFER))))
        .isEqualTo("Bank transfer (2 payments)");
    assertThat(ExpenseSettlementService.methodNote(List.of(
        payment(ExpenseSettlement.BANK_TRANSFER), payment(ExpenseSettlement.CASH),
        payment(ExpenseSettlement.OTHER))))
        .isEqualTo("Bank transfer and cash and other payment (3 payments)");
  }

  private static ExpenseSettlement.Payment payment(final String method) {
    return new ExpenseSettlement.Payment(method, 100, DAY, null);
  }
}
