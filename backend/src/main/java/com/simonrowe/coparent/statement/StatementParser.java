package com.simonrowe.coparent.statement;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the five supported bank and card exports into money-out transactions. Parsing is
 * deterministic on purpose: amounts and dates never pass through a model, which only ever sees
 * the result and suggests which rows might be shared.
 *
 * <p>Money coming in (refunds, transfers, card repayments) is counted and dropped, never stored:
 * it cannot become an expense, and its descriptions name whoever sent it.
 *
 * <p>Each transaction gets a fingerprint from the file's own fields, so uploading the same
 * statement twice, or two that overlap, stores each transaction once. Identical rows on the same
 * day (two bus fares) are told apart by their position among themselves, which is stable as long
 * as a statement holds whole days, as bank exports do.
 */
public final class StatementParser {

  public static final int MAX_ROWS = 5_000;
  static final int MAX_DESCRIPTION = 200;
  static final int MAX_DETAILS = 300;
  private static final long MAX_PENCE = 100_000_000;
  private static final String TOO_MANY =
      "A statement can hold up to %d transactions. Upload a shorter period.";

  private static final DateTimeFormatter UK_DATE =
      DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter ISO_DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern COLUMN_GAP = Pattern.compile(" {2,}+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s++");
  private static final Pattern CURRENCY_SUFFIX = Pattern.compile("^(\\S{1,20}+)\\s++([A-Z]{3})$");
  private static final Pattern LAST_FOUR = Pattern.compile("(\\d{4})\\s*+$");
  private static final Pattern PAYMENT_DATE = Pattern.compile("\\d{2}-\\d{2}-\\d{4}");
  private static final String CARD_PAYMENT = "CARD PAYMENT TO ";
  private static final String DIRECT_DEBIT = "DIRECT DEBIT PAYMENT TO ";
  private static final List<String> BILL_PAYMENTS = List.of(
      "BILL PAYMENT VIA FASTER PAYMENT TO ", "BILL PAYMENT TO ");
  private static final List<String> STANDING_ORDERS = List.of(
      "STANDING ORDER VIA FASTER PAYMENT TO ", "STANDING ORDER TO ");

  /** A statement that could not be read. The message is safe to show the parent. */
  public static final class UnreadableStatementException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UnreadableStatementException(final String message) {
      super(message);
    }
  }

  /** One money-out transaction, in pence, with the fingerprint that keeps it stored once. */
  public record ParsedTransaction(
      LocalDate date,
      String description,
      String details,
      long amountPence,
      String fingerprint
  ) {
  }

  /** What a file held: its spending, and how many rows were money in or could not be read. */
  public record ParsedStatement(
      StatementFormat format,
      String lastFour,
      List<ParsedTransaction> debits,
      int credits,
      int unreadable,
      LocalDate from,
      LocalDate to
  ) {
    public ParsedStatement {
      debits = List.copyOf(debits);
    }

    public String account() {
      return format.account(lastFour);
    }
  }

  /** Recognises the file's format from its layout and reads every row. */
  public ParsedStatement parse(final byte[] bytes) {
    final String text = normalise(decode(bytes));
    final String firstLine = text.lines().filter(line -> !line.isBlank()).findFirst()
        .orElseThrow(() -> new UnreadableStatementException("The file is empty"));
    if (firstLine.startsWith("From:") && text.contains("\nDate:")) {
      return santanderCurrent(text);
    }
    if (text.lines().anyMatch(StatementParser::isSantanderCardHeader)) {
      return santanderCreditCard(text);
    }
    final List<List<String>> rows = Csv.read(text);
    final Header header = new Header(rows.getFirst());
    if (header.has("counter party") && header.has("amount (gbp)")) {
      return starling(header, rows);
    }
    if (header.has("transaction id") && header.has("name") && header.has("amount")) {
      return monzo(header, rows);
    }
    if (header.has("date") && header.has("description") && header.has("amount")) {
      return amex(header, rows);
    }
    throw new UnreadableStatementException(unrecognised());
  }

  static String unrecognised() {
    return """
        This doesn't look like a statement CoParent can read. Upload a Starling, Monzo or \
        American Express CSV, or a Santander current account or credit card TXT export.""";
  }

  // ---- formats -----------------------------------------------------------------------------

  private ParsedStatement starling(final Header header, final List<List<String>> rows) {
    final Builder builder = new Builder(StatementFormat.STARLING, null);
    for (final List<String> row : rows.subList(1, rows.size())) {
      if (blank(row)) {
        continue;
      }
      final LocalDate date = date(header.get(row, "date"), UK_DATE);
      final Long pence = pence(header.get(row, "amount (gbp)"));
      if (date == null || pence == null) {
        builder.unreadable();
        continue;
      }
      final String counterParty = clean(header.get(row, "counter party"));
      final String reference = clean(header.get(row, "reference"));
      final String type = clean(header.get(row, "type"));
      final String category = clean(header.get(row, "spending category"))
          .replace('_', ' ').toLowerCase(Locale.UK);
      // Notes and tags are left out of the key: the parent can edit them in the bank's app,
      // and a re-export would then store the same payment twice.
      builder.add(date, -pence, counterParty.isEmpty() ? reference : counterParty,
          details(reference.equalsIgnoreCase(counterParty) ? "" : reference, type, category,
              clean(header.get(row, "notes"))),
          String.join("|", header.get(row, "date"), counterParty, reference, type,
              header.get(row, "amount (gbp)"), header.get(row, "balance (gbp)")));
    }
    return builder.build();
  }

  private ParsedStatement monzo(final Header header, final List<List<String>> rows) {
    final Builder builder = new Builder(StatementFormat.MONZO, null);
    for (final List<String> row : rows.subList(1, rows.size())) {
      if (blank(row)) {
        continue;
      }
      final LocalDate date = date(header.get(row, "date"), UK_DATE);
      final Long pence = pence(header.get(row, "amount"));
      if (date == null || pence == null) {
        builder.unreadable();
        continue;
      }
      final String name = clean(header.get(row, "name"));
      final String statementText = columns(header.get(row, "description"));
      final String type = clean(header.get(row, "type"));
      final String id = header.get(row, "transaction id").trim();
      builder.add(date, -pence,
          !name.isEmpty() ? name : !statementText.isEmpty() ? statementText : type,
          details(type, clean(header.get(row, "category")),
              clean(header.get(row, "notes and #tags")),
              statementText.equalsIgnoreCase(name) ? "" : statementText),
          // Monzo gives every transaction its own id, which is the steadiest key there is.
          id.isEmpty() ? String.join("|", row) : "id:" + id);
    }
    return builder.build();
  }

  private ParsedStatement amex(final Header header, final List<List<String>> rows) {
    final Builder builder = new Builder(StatementFormat.AMEX, null);
    for (final List<String> row : rows.subList(1, rows.size())) {
      if (blank(row)) {
        continue;
      }
      final LocalDate date = date(header.get(row, "date"), UK_DATE);
      final Long pence = pence(header.get(row, "amount"));
      if (date == null || pence == null) {
        builder.unreadable();
        continue;
      }
      final List<String> parts = parts(header.get(row, "description"));
      // American Express lists charges as positive and payments to the card as negative.
      builder.add(date, pence, parts.isEmpty() ? "" : parts.getFirst(),
          String.join(", ", parts.subList(Math.min(1, parts.size()), parts.size())),
          String.join("|", header.get(row, "date"), header.get(row, "description"),
              header.get(row, "amount")));
    }
    return builder.build();
  }

  private ParsedStatement santanderCurrent(final String text) {
    final Matcher account = Pattern.compile("(?m)^Account:([^\\n]*+)$").matcher(text);
    final String lastFour = account.find() ? lastFour(account.group(1)) : null;
    final Builder builder = new Builder(StatementFormat.SANTANDER_CURRENT, lastFour);
    String date = null;
    String description = null;
    String amount = null;
    for (final String line : text.lines().map(String::strip).toList()) {
      if (line.startsWith("Date:")) {
        date = value(line);
        description = null;
        amount = null;
      } else if (line.startsWith("Description:")) {
        description = unescape(value(line));
      } else if (line.startsWith("Amount:")) {
        amount = value(line);
      } else if (line.startsWith("Balance:") && date != null) {
        santanderCurrentRow(builder, date, description, amount, value(line));
        date = null;
      }
    }
    return builder.build();
  }

  private void santanderCurrentRow(final Builder builder, final String dateText,
      final String descriptionText, final String amountText, final String balance) {
    final LocalDate date = date(dateText, UK_DATE);
    final Long pence = pence(amountText);
    if (date == null || pence == null || descriptionText == null) {
      builder.unreadable();
      return;
    }
    final String raw = cap(clean(descriptionText), MAX_DESCRIPTION);
    final String[] parts = santanderParts(raw);
    final String description = parts[0];
    final String details = parts[1];
    builder.add(date, -pence, description, details,
        String.join("|", dateText, descriptionText, amountText, balance));
  }

  /**
   * The payee and the rest of a Santander description, read with plain string searches rather
   * than a regular expression: these lines come from a bank and are untrusted, and nothing here
   * can backtrack.
   */
  static String[] santanderParts(final String raw) {
    if (raw.startsWith(CARD_PAYMENT)) {
      final int on = raw.lastIndexOf(" ON ");
      final String paid = on < 0 ? "" : raw.substring(on + 4).strip();
      if (on > CARD_PAYMENT.length() && PAYMENT_DATE.matcher(paid).matches()) {
        return new String[] {raw.substring(CARD_PAYMENT.length(), on).strip(),
            "Card payment on " + paid};
      }
    }
    if (raw.startsWith(DIRECT_DEBIT)) {
      final int mandate = raw.lastIndexOf(", MANDATE NO ");
      final String head = mandate < 0 ? raw : raw.substring(0, mandate);
      final int ref = head.lastIndexOf(" REF ");
      if (ref > DIRECT_DEBIT.length()) {
        return new String[] {head.substring(DIRECT_DEBIT.length(), ref).strip(),
            "Direct debit, ref " + head.substring(ref + 5).strip()};
      }
    }
    final String[] payment = transfer(raw, BILL_PAYMENTS, "Payment");
    if (payment != null) {
      return payment;
    }
    final String[] order = transfer(raw, STANDING_ORDERS, "Standing order");
    return order != null ? order : new String[] {raw, ""};
  }

  /** A payment to a person or business with a reference: its payee and details, or null. */
  private static String[] transfer(final String raw, final List<String> prefixes,
      final String kind) {
    for (final String prefix : prefixes) {
      if (!raw.startsWith(prefix)) {
        continue;
      }
      final int reference = raw.indexOf(" REFERENCE ", prefix.length());
      if (reference <= prefix.length()) {
        return null;
      }
      String ref = raw.substring(reference + " REFERENCE ".length());
      // The export cuts long lines short, so the mandate suffix can end mid-word ("MAN").
      final int mandate = ref.lastIndexOf(", M");
      if (mandate >= 0 && ref.substring(mandate + 2).chars()
          .allMatch(c -> Character.isUpperCase(c) || Character.isDigit(c) || c == ' ')) {
        ref = ref.substring(0, mandate);
      }
      return new String[] {raw.substring(prefix.length(), reference).strip(),
          "%s, ref %s".formatted(kind, ref.strip())};
    }
    return null;
  }

  private ParsedStatement santanderCreditCard(final String text) {
    final List<String> lines = text.lines().toList();
    int start = 0;
    while (!isSantanderCardHeader(lines.get(start))) {
      start++;
    }
    String lastFour = null;
    final List<Row> rows = new ArrayList<>();
    for (final String line : lines.subList(start + 1, lines.size())) {
      if (line.isBlank() || line.startsWith("---")) {
        continue;
      }
      final String[] fields = line.split("\t", -1);
      String card = null;
      String description = null;
      int index = 1;
      for (; index < fields.length; index++) {
        final String field = fields[index].trim();
        if (field.startsWith("**")) {
          card = field;
        } else if (!field.isEmpty()) {
          description = fields[index];
          break;
        }
      }
      if (card != null && lastFour == null) {
        lastFour = lastFour(card);
      }
      rows.add(new Row(List.of(fields), index, description));
    }
    final Builder builder = new Builder(StatementFormat.SANTANDER_CREDIT_CARD, lastFour);
    for (final Row row : rows) {
      santanderCardRow(builder, row);
    }
    return builder.build();
  }

  private record Row(List<String> fields, int descriptionIndex, String description) {
  }

  private void santanderCardRow(final Builder builder, final Row row) {
    final LocalDate date = date(row.fields().getFirst().trim(), ISO_DATE);
    if (date == null || row.description() == null) {
      builder.unreadable();
      return;
    }
    // Money out is the last column, so a payment out ends the line; money in is followed by
    // the empty money-out column. The column, not the sign, says which way it went.
    final String[] after = row.fields().subList(row.descriptionIndex() + 1, row.fields().size())
        .toArray(String[]::new);
    int last = after.length - 1;
    while (last >= 0 && after[last].isBlank()) {
      last--;
    }
    final Long pence = last < 0 ? null : pence(after[last]);
    if (pence == null) {
      builder.unreadable();
      return;
    }
    final boolean moneyOut = last == after.length - 1;
    final List<String> parts = parts(row.description());
    final String description = parts.size() >= 3 ? parts.getLast() : String.join(" ", parts);
    final String details = parts.size() >= 3
        ? String.join(", ", parts.subList(0, parts.size() - 1)) : "";
    builder.add(date, moneyOut ? pence : -pence, description, details,
        String.join("|", row.fields().getFirst().trim(), clean(row.description()),
            after[last].trim(),
            moneyOut ? "out" : "in"));
  }

  private static boolean isSantanderCardHeader(final String line) {
    return line.contains("Card no.") && line.contains("Money in") && line.contains("Money out");
  }

  // ---- building ----------------------------------------------------------------------------

  /** Collects rows, counting identical ones so each keeps a distinct, repeatable fingerprint. */
  private static final class Builder {
    private final StatementFormat format;
    private final String lastFour;
    private final List<ParsedTransaction> debits = new ArrayList<>();
    private final Map<String, Integer> seen = new HashMap<>();
    private int credits;
    private int unreadable;
    private int rows;
    private LocalDate from;
    private LocalDate to;

    Builder(final StatementFormat format, final String lastFour) {
      this.format = format;
      this.lastFour = lastFour;
    }

    void unreadable() {
      count();
      unreadable++;
    }

    /** Adds a row. A positive {@code spentPence} is money out; zero or negative is money in. */
    void add(final LocalDate date, final long spentPence, final String description,
        final String details, final String key) {
      count();
      from = from == null || date.isBefore(from) ? date : from;
      to = to == null || date.isAfter(to) ? date : to;
      if (spentPence <= 0) {
        credits++;
        return;
      }
      if (spentPence > MAX_PENCE) {
        unreadable++;
        return;
      }
      final int occurrence = seen.merge(key, 1, Integer::sum);
      debits.add(new ParsedTransaction(date,
          cap(description.isBlank() ? "Card payment" : description, MAX_DESCRIPTION),
          cap(details, MAX_DETAILS), spentPence,
          fingerprint(format, lastFour, key, occurrence)));
    }

    private void count() {
      if (++rows > MAX_ROWS) {
        throw new UnreadableStatementException(TOO_MANY.formatted(MAX_ROWS));
      }
    }

    ParsedStatement build() {
      if (rows > 0 && debits.isEmpty() && credits == 0) {
        throw new UnreadableStatementException(
            "None of the transactions in this %s statement could be read."
                .formatted(format.label()));
      }
      if (rows == 0) {
        throw new UnreadableStatementException(
            "The file is a %s statement, but it has no transactions in it."
                .formatted(format.label()));
      }
      return new ParsedStatement(format, lastFour, debits, credits, unreadable, from, to);
    }
  }

  static String fingerprint(final StatementFormat format, final String lastFour,
      final String key, final int occurrence) {
    final String material = String.join("|", "v1", format.name(),
        lastFour == null ? "" : lastFour, key.toLowerCase(Locale.UK), String.valueOf(occurrence));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  // ---- text --------------------------------------------------------------------------------

  /**
   * UTF-8 when the bytes are valid UTF-8, Windows-1252 otherwise. Santander's text exports are
   * single-byte, with a non-breaking space after every label, and decoding them as UTF-8 would
   * replace each one with an error character.
   */
  static String decode(final byte[] bytes) {
    int offset = 0;
    if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
        && (bytes[2] & 0xff) == 0xbf) {
      offset = 3;
    }
    final ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(buffer).toString();
    } catch (CharacterCodingException notUtf8) {
      return new String(bytes, offset, bytes.length - offset, Charset.forName("windows-1252"));
    }
  }

  private static String normalise(final String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ');
  }

  private static String value(final String line) {
    return line.substring(line.indexOf(':') + 1).strip();
  }

  /** Santander's export escapes ampersands and friends as HTML, so M&S arrives as M&amp;S. */
  private static String unescape(final String text) {
    return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
  }

  private static String clean(final String text) {
    return text == null ? "" : WHITESPACE.matcher(text.strip()).replaceAll(" ");
  }

  /** Fixed-width text such as {@code FOREST BIKES            LONDON} as separate parts. */
  private static List<String> parts(final String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(COLUMN_GAP.split(text.strip()))
        .map(StatementParser::clean).filter(part -> !part.isEmpty()).toList();
  }

  private static String columns(final String text) {
    return String.join(", ", parts(text));
  }

  private static String details(final String... parts) {
    final Set<String> unique = new LinkedHashSet<>();
    for (final String part : parts) {
      if (part != null && !part.isBlank()) {
        unique.add(part.strip());
      }
    }
    return String.join(", ", unique);
  }

  private static String cap(final String text, final int max) {
    return text.length() <= max ? text : text.substring(0, max).strip();
  }

  private static String lastFour(final String text) {
    final Matcher matcher = LAST_FOUR.matcher(text.strip());
    return matcher.find() ? matcher.group(1) : null;
  }

  private static LocalDate date(final String text, final DateTimeFormatter format) {
    try {
      return text == null ? null : LocalDate.parse(text.strip(), format);
    } catch (DateTimeParseException invalid) {
      return null;
    }
  }

  /** Pounds as written, to exact pence. Fractions of a penny are unreadable, never rounded. */
  static Long pence(final String text) {
    if (text == null) {
      return null;
    }
    String number = text.strip().replace(",", "").replace("£", "");
    // Santander's older rows read "-4.40 GBP". Another currency is unreadable, never converted.
    final Matcher currency = CURRENCY_SUFFIX.matcher(number);
    if (currency.matches()) {
      if (!"GBP".equals(currency.group(2))) {
        return null;
      }
      number = currency.group(1);
    }
    if (number.isEmpty() || number.length() > 20) {
      return null;
    }
    try {
      return new BigDecimal(number).movePointRight(2).longValueExact();
    } catch (NumberFormatException | ArithmeticException invalid) {
      return null;
    }
  }

  private static boolean blank(final List<String> row) {
    return row.stream().allMatch(String::isBlank);
  }

  /** Column lookup by header name, ignoring case and surrounding space. */
  private static final class Header {
    private final Map<String, Integer> positions = new HashMap<>();

    Header(final List<String> names) {
      for (int index = 0; index < names.size(); index++) {
        positions.putIfAbsent(names.get(index).strip().toLowerCase(Locale.UK), index);
      }
    }

    boolean has(final String name) {
      return positions.containsKey(name);
    }

    String get(final List<String> row, final String name) {
      final Integer position = positions.get(name);
      return position == null || position >= row.size() ? "" : row.get(position);
    }
  }

  /** RFC 4180 CSV: quoted fields may hold commas, doubled quotes and line breaks. */
  static final class Csv {
    private Csv() {
    }

    static List<List<String>> read(final String text) {
      final List<List<String>> rows = new ArrayList<>();
      List<String> row = new ArrayList<>();
      final StringBuilder field = new StringBuilder();
      boolean quoted = false;
      for (int index = 0; index < text.length(); index++) {
        final char character = text.charAt(index);
        if (quoted) {
          if (character == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
            field.append('"');
            index++;
          } else if (character == '"') {
            quoted = false;
          } else {
            field.append(character);
          }
        } else if (character == '"' && field.isEmpty()) {
          quoted = true;
        } else if (character == ',') {
          row.add(field.toString());
          field.setLength(0);
        } else if (character == '\n') {
          row.add(field.toString());
          field.setLength(0);
          rows.add(row);
          row = new ArrayList<>();
          if (rows.size() > MAX_ROWS + 1) {
            throw new UnreadableStatementException(TOO_MANY.formatted(MAX_ROWS));
          }
        } else {
          field.append(character);
        }
      }
      if (!field.isEmpty() || !row.isEmpty()) {
        row.add(field.toString());
        rows.add(row);
      }
      if (rows.isEmpty()) {
        throw new UnreadableStatementException("The file is empty");
      }
      return rows;
    }
  }
}
