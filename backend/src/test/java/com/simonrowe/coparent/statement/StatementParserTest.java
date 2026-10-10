package com.simonrowe.coparent.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.coparent.statement.StatementParser.ParsedStatement;
import com.simonrowe.coparent.statement.StatementParser.ParsedTransaction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each bank's layout, read from synthetic files built to match the real exports byte for byte in
 * shape. Real statements never go in this public repository.
 */
class StatementParserTest {

  private final StatementParser parser = new StatementParser();

  @Test
  void readsSantanderCurrentAccountTextInWindows1252() throws IOException {
    final ParsedStatement parsed = parse("santander-current.txt");

    assertThat(parsed.format()).isEqualTo(StatementFormat.SANTANDER_CURRENT);
    assertThat(parsed.account()).isEqualTo("Santander current account ··1234");
    assertThat(parsed.credits()).isEqualTo(1);
    // The euro row is unreadable: another currency is never taken as pounds.
    assertThat(parsed.unreadable()).isEqualTo(1);
    // The unreadable row says nothing reliable, so it does not widen the period.
    assertThat(parsed.from()).isEqualTo(LocalDate.of(2026, 5, 12));
    assertThat(parsed.to()).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(parsed.debits()).extracting(ParsedTransaction::description).containsExactly(
        "EXAMPLE MORTGAGES", "CHEMIST 0001", "KIDS GYM CLUB", "EXAMPLE DANCE SCHOOL",
        "MARKS&SPENCER PLC", "EXAMPLE CAFE & BISTRO");
    assertThat(parsed.debits()).extracting(ParsedTransaction::details).containsExactly(
        "Direct debit, ref 1111111111", "Card payment on 05-10-2026",
        "Card payment on 04-10-2026", "Payment, ref Robin Exam", "Card payment on 01-10-2026",
        "Card payment on 11-05-2026");
    assertThat(parsed.debits()).extracting(ParsedTransaction::amountPence)
        .containsExactly(120000L, 1250L, 11700L, 4800L, 2000L, 440L);
  }

  @Test
  void readsSantanderCreditCardColumnsByPositionNotSign() throws IOException {
    final ParsedStatement parsed = parse("santander-credit-card.txt");

    assertThat(parsed.format()).isEqualTo(StatementFormat.SANTANDER_CREDIT_CARD);
    assertThat(parsed.account()).isEqualTo("Santander credit card ··5678");
    // Cashback sits in the money-in column and is dropped.
    assertThat(parsed.credits()).isEqualTo(1);
    assertThat(parsed.debits()).extracting(ParsedTransaction::description).containsExactly(
        "EXAMPLE SHOES LTD", "EXAMPLE BOOKS", "EXAMPLE BOOKS", "ACCOUNT FEE");
    assertThat(parsed.debits().getFirst().details()).isEqualTo("PURCHASE - DOMESTIC, LONDON");
    assertThat(parsed.debits()).extracting(ParsedTransaction::amountPence)
        .containsExactly(3499L, 1295L, 1295L, 300L);
  }

  @Test
  void readsStarlingAndDropsMoneyIn() throws IOException {
    final ParsedStatement parsed = parse("starling.csv");

    assertThat(parsed.format()).isEqualTo(StatementFormat.STARLING);
    assertThat(parsed.account()).isEqualTo("Starling");
    assertThat(parsed.credits()).isEqualTo(1);
    assertThat(parsed.debits()).hasSize(4);
    assertThat(parsed.debits().get(2).description()).isEqualTo("Example School Shop");
    assertThat(parsed.debits().get(2).details())
        .isEqualTo("UNIFORM ORDER, ONLINE PAYMENT, shopping");
    assertThat(parsed.debits().get(3).details()).isEqualTo("CONTACTLESS, groceries, weekly shop");
  }

  @Test
  void readsMonzoWithQuotedCommasAndLineBreaks() throws IOException {
    final ParsedStatement parsed = parse("monzo.csv");

    assertThat(parsed.format()).isEqualTo(StatementFormat.MONZO);
    // The money in and the zero-value card check are both left out.
    assertThat(parsed.credits()).isEqualTo(2);
    assertThat(parsed.debits()).extracting(ParsedTransaction::description)
        .containsExactly("Example Taxis", "Example Taxis", "Swim School, Junior");
    assertThat(parsed.debits().get(2).details())
        .isEqualTo("Card payment, Entertainment, lessons, autumn term, SWIM SCHOOL, London, GBR");
  }

  @Test
  void readsAmericanExpressWhereChargesArePositive() throws IOException {
    final ParsedStatement parsed = parse("amex.csv");

    assertThat(parsed.format()).isEqualTo(StatementFormat.AMEX);
    assertThat(parsed.credits()).isEqualTo(1);
    assertThat(parsed.debits()).extracting(ParsedTransaction::description).containsExactly(
        "INTEREST CHARGE", "EXAMPLE BIKES", "EXAMPLE BIKES", "Collctiv Class Gift Example");
    assertThat(parsed.debits().get(1).details()).isEqualTo("LONDON");
  }

  @Test
  void identicalRowsOnOneDayKeepDistinctFingerprintsThatRepeatOnReupload() throws IOException {
    for (final String file : List.of("santander-current.txt", "santander-credit-card.txt",
        "starling.csv", "monzo.csv", "amex.csv")) {
      final List<String> first = fingerprints(parse(file));
      assertThat(first).as(file).doesNotHaveDuplicates().allMatch(id -> id.matches("[0-9a-f]{64}"));
      assertThat(fingerprints(parse(file))).as(file).isEqualTo(first);
    }
  }

  @Test
  void fingerprintsAreScopedToTheFormatAndAccount() {
    final String key = "05/09/2026|EXAMPLE|2.32";
    assertThat(StatementParser.fingerprint(StatementFormat.AMEX, null, key, 1))
        .isNotEqualTo(StatementParser.fingerprint(StatementFormat.STARLING, null, key, 1))
        .isNotEqualTo(StatementParser.fingerprint(StatementFormat.AMEX, "1234", key, 1))
        .isNotEqualTo(StatementParser.fingerprint(StatementFormat.AMEX, null, key, 2));
  }

  @Test
  void refusesFilesThatAreNotStatements() {
    assertThatThrownBy(() -> parser.parse("name,email\nRobin,robin@example.com\n"
        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(StatementParser.UnreadableStatementException.class)
        .hasMessageContaining("Starling, Monzo or");
    assertThatThrownBy(() -> parser.parse(new byte[0]))
        .isInstanceOf(StatementParser.UnreadableStatementException.class);
    assertThatThrownBy(() -> parser.parse("Date,Description,Amount\n".getBytes(
        StandardCharsets.UTF_8)))
        .hasMessageContaining("no transactions");
    assertThatThrownBy(() -> parser.parse("Date,Description,Amount\nsoon,thing,lots\n"
        .getBytes(StandardCharsets.UTF_8)))
        .hasMessageContaining("could be read");
  }

  @Test
  void refusesMoreRowsThanTheLimit() {
    final StringBuilder csv = new StringBuilder("Date,Description,Amount\n");
    for (int row = 0; row <= StatementParser.MAX_ROWS; row++) {
      csv.append("01/09/2026,SHOP ").append(row).append(",1.00\n");
    }
    assertThatThrownBy(() -> parser.parse(csv.toString().getBytes(StandardCharsets.UTF_8)))
        .hasMessageContaining("up to " + StatementParser.MAX_ROWS);
  }

  @Test
  void splitsSantanderDescriptionsIntoPayeeAndDetails() {
    assertThat(StatementParser.santanderParts("CARD PAYMENT TO TOWN CAFE ON 2 ON 01-10-2026"))
        .containsExactly("TOWN CAFE ON 2", "Card payment on 01-10-2026");
    assertThat(StatementParser.santanderParts(
        "DIRECT DEBIT PAYMENT TO WATER CO REF 12 REF 34, MANDATE NO 0001"))
        .containsExactly("WATER CO", "Direct debit, ref 12 REF 34");
    assertThat(StatementParser.santanderParts(
        "BILL PAYMENT TO A TUTOR REFERENCE Robin maths, MANDATE NO00143"))
        .containsExactly("A TUTOR", "Payment, ref Robin maths");
    assertThat(StatementParser.santanderParts(
        "STANDING ORDER VIA FASTER PAYMENT TO EXAMPLE CLUB REFERENCE Robin , MANDAT"))
        .containsExactly("EXAMPLE CLUB", "Standing order, ref Robin");
    // A reference that merely contains ", M" followed by ordinary words is kept whole.
    assertThat(StatementParser.santanderParts(
        "BILL PAYMENT TO A FRIEND REFERENCE lunch, Monday treat"))
        .containsExactly("A FRIEND", "Payment, ref lunch, Monday treat");
    // Without its mandate number a direct debit is not split, exactly as before.
    assertThat(StatementParser.santanderParts("DIRECT DEBIT PAYMENT TO WATER CO REF 34, MAND"))
        .containsExactly("DIRECT DEBIT PAYMENT TO WATER CO REF 34, MAND", "");
    assertThat(StatementParser.santanderParts("DIRECT DEBIT PAYMENT TO WATER CO REF 34"))
        .containsExactly("DIRECT DEBIT PAYMENT TO WATER CO REF 34", "");
    assertThat(StatementParser.santanderParts("CARD PAYMENT TO SHOP ON SOMEDAY"))
        .containsExactly("CARD PAYMENT TO SHOP ON SOMEDAY", "");
    assertThat(StatementParser.santanderParts("MONTHLY FEE")).containsExactly("MONTHLY FEE", "");
  }

  @Test
  void readsPenceExactlyAndRefusesFractionsOfPennies() {
    assertThat(StatementParser.pence("-2,502.73")).isEqualTo(-250273L);
    assertThat(StatementParser.pence("£4.5")).isEqualTo(450L);
    assertThat(StatementParser.pence("1.234")).isNull();
    assertThat(StatementParser.pence("abc")).isNull();
    assertThat(StatementParser.pence("10.00 USD")).isNull();
  }

  @Test
  void longHostileDescriptionsParseQuickly() {
    // The description patterns run on lines already capped in length, so a pathological
    // payee cannot make them backtrack for long.
    final String hostile = "CARD PAYMENT TO " + "A ".repeat(60_000) + "ON";
    final String text = """
        From:\u00a001/09/2026\u00a0to\u00a002/09/2026
        Account:\u00a01234
        Date:\u00a001/09/2026
        Description:\u00a0%s
        Amount:\u00a0-1.00
        Balance:\u00a01.00
        """.formatted(hostile);
    final long started = System.nanoTime();
    final ParsedStatement parsed = parser.parse(text.getBytes(StandardCharsets.UTF_8));
    assertThat(parsed.debits()).hasSize(1);
    assertThat(parsed.debits().getFirst().description()).hasSizeLessThanOrEqualTo(
        StatementParser.MAX_DESCRIPTION);
    assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
  }

  private ParsedStatement parse(final String file) throws IOException {
    try (InputStream input = getClass().getResourceAsStream("/coparent/statements/" + file)) {
      assertThat(input).as(file).isNotNull();
      return parser.parse(input.readAllBytes());
    }
  }

  private static List<String> fingerprints(final ParsedStatement parsed) {
    return parsed.debits().stream().map(ParsedTransaction::fingerprint).toList();
  }
}
