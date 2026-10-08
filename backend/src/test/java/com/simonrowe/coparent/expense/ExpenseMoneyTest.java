package com.simonrowe.coparent.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpenseMoneyTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "4500|£45.00",
      "1|£0.01",
      "123450|£1,234.50",
      "10000000|£100,000.00"
  })
  void formatsPenceAsPoundsSterling(final long pence, final String expected) {
    assertThat(ExpenseMoney.format(pence)).isEqualTo(expected);
  }

  @Test
  void staysInPoundsWhenTheJvmDefaultsToTheUnitedStates() {
    final Locale before = Locale.getDefault();
    try {
      Locale.setDefault(Locale.US);
      assertThat(ExpenseMoney.format(4500)).isEqualTo("£45.00").doesNotContain("$");
    } finally {
      Locale.setDefault(before);
    }
  }
}
