package com.simonrowe.coparent.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.coparent.expense.ExpenseReceiptService.ReceiptFile;
import com.simonrowe.coparent.model.Expense;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpenseReceiptServiceTest {

  @Test
  void recognisesReceiptsByTheirOwnBytes() {
    assertThat(ExpenseReceiptService.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00)))
        .isEqualTo("image/jpeg");
    assertThat(ExpenseReceiptService.sniff(
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00))).isEqualTo("image/png");
    assertThat(ExpenseReceiptService.sniff("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("application/pdf");
  }

  @Test
  void refusesAnythingElseWhateverItIsCalled() {
    // An iPhone HEIC: "....ftypheic". Renaming it receipt.jpg changes nothing here.
    assertThat(ExpenseReceiptService.sniff(
        bytes(0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'))).isNull();
    assertThat(ExpenseReceiptService.sniff("<svg xmlns=\"x\"/>".getBytes(StandardCharsets.UTF_8)))
        .isNull();
    assertThat(ExpenseReceiptService.sniff(bytes(0xFF, 0xD8))).isNull();
    assertThat(ExpenseReceiptService.sniff(new byte[0])).isNull();
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', nullValues = "NULL", value = {
      "clarks-receipt.jpg|clarks-receipt.jpg",
      "../../etc/passwd|passwd",
      "C:\\Users\\sam\\Photos\\IMG_0042.JPG|IMG_0042.JPG",
      "<script>.pdf|script.pdf",
      "...///|receipt",
      "NULL|receipt",
      "  |receipt"
  })
  void displayNamesAreTheBareFileNameInPlainCharacters(final String original,
      final String expected) {
    assertThat(ExpenseReceiptService.displayName(original)).isEqualTo(expected);
  }

  @Test
  void longNamesAreCut() {
    assertThat(ExpenseReceiptService.displayName("a".repeat(300) + ".jpg")).hasSize(100);
  }

  @Test
  void receiptFilesCompareByTheirBytesAndNeverPrintThem() {
    final Expense.Receipt receipt = new Expense.Receipt(new ObjectId(), "image/png", 6,
        "receipt.png", new ObjectId(), Instant.parse("2026-10-08T09:00:00Z"));
    final ReceiptFile one = new ReceiptFile(receipt, 1, "SECRET".getBytes(StandardCharsets.UTF_8));
    final ReceiptFile same = new ReceiptFile(receipt, 1,
        "SECRET".getBytes(StandardCharsets.UTF_8));
    final ReceiptFile other = new ReceiptFile(receipt, 1,
        "OTHERS".getBytes(StandardCharsets.UTF_8));

    assertThat(one).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
    assertThat(one.toString()).contains("<6 bytes>").doesNotContain("SECRET")
        .doesNotContain("[B@");
  }

  private static byte[] bytes(final int... values) {
    final byte[] out = new byte[values.length];
    for (int index = 0; index < values.length; index++) {
      out[index] = (byte) values[index];
    }
    return out;
  }
}
