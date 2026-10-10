package com.simonrowe.coparent.statement;

import com.simonrowe.coparent.expense.ExpenseService;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.model.StatementTransaction;
import com.simonrowe.coparent.model.StatementUpload;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.ExpenseRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bank statement uploads and the shared-cost suggestions made from them. Everything here belongs
 * to the parent who uploaded it: the other parent sees an expense only once one is created, and
 * never the statement or the suggestions.
 *
 * <p>An upload is read and answered with every spending row, which the page holds while the
 * parent is on it. The page then sends the rows not seen before back in batches to be checked,
 * so no request runs long and nothing of the statement waits on the server to be classified.
 * Each checked row is recorded by fingerprint, unique per parent, which is what makes uploading
 * the same statement twice add nothing and stops one transaction being logged twice.
 */
@Service
public class StatementService {

  public static final int MAX_BYTES = 2 * 1024 * 1024;
  static final int MAX_UPLOADS_LISTED = 20;
  static final int MAX_LISTED = 500;
  static final int MAX_DECISIONS = 40;
  static final int MATCH_DAYS = 3;
  static final Duration CHECKING_GRACE = Duration.ofMinutes(2);
  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
  private static final Logger log = LoggerFactory.getLogger(StatementService.class);

  private final StatementParser parser = new StatementParser();
  private final StatementClassifier classifier;
  private final StatementProperties properties;
  private final ExpenseService expenseService;
  private final ExpenseRepository expenses;
  private final ChildRepository children;
  private final ParentRepository parents;
  private final CoparentAccessPolicy access;
  private final CoparentAuditService audits;
  private final MongoTemplate mongoTemplate;
  private final Clock clock;

  @Autowired
  public StatementService(
      final StatementClassifier classifier,
      final StatementProperties properties,
      final ExpenseService expenseService,
      final ExpenseRepository expenses,
      final ChildRepository children,
      final ParentRepository parents,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this(classifier, properties, expenseService, expenses, children, parents, access, audits,
        mongoTemplate, Clock.systemUTC());
  }

  StatementService(
      final StatementClassifier classifier,
      final StatementProperties properties,
      final ExpenseService expenseService,
      final ExpenseRepository expenses,
      final ChildRepository children,
      final ParentRepository parents,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      final MongoTemplate mongoTemplate,
      final Clock clock) {
    this.classifier = classifier;
    this.properties = properties;
    this.expenseService = expenseService;
    this.expenses = expenses;
    this.children = children;
    this.parents = parents;
    this.access = access;
    this.audits = audits;
    this.mongoTemplate = mongoTemplate;
    this.clock = clock;
  }

  // ---- views -------------------------------------------------------------------------------

  /** A live expense that looks like the same payment: same amount, within a few days. */
  public record Match(ObjectId id, String title, LocalDate date) {
  }

  /**
   * One spending row of an upload, as the page shows it. {@code state} is null for a row never
   * seen before, or what became of it when it was: checked, suggested, dismissed or logged.
   */
  public record Row(
      String fingerprint,
      LocalDate date,
      String description,
      String details,
      long amountPence,
      String account,
      String state,
      ObjectId transactionId,
      ObjectId expenseId,
      Match match
  ) {
  }

  /** What reading a file did. {@code upload} is null when every row had been seen before. */
  public record UploadResult(
      StatementUpload upload,
      String format,
      String account,
      LocalDate from,
      LocalDate to,
      int moneyIn,
      int unreadable,
      List<Row> rows,
      boolean aiEnabled
  ) {
  }

  /** The verdict on one checked row. */
  public record CheckResult(String fingerprint, String state, StatementTransaction transaction) {
  }

  /** A row the page sends back to be checked or logged, as the upload returned it. */
  public record RowInput(
      String fingerprint,
      LocalDate date,
      String description,
      String details,
      long amountPence,
      String account
  ) {
  }

  /** How far one upload's checking got, as the uploads list shows it. */
  public record UploadView(StatementUpload upload, String status) {
  }

  /** The statements page's header: the switch, the tab counts and recent uploads. */
  public record Overview(
      boolean aiEnabled,
      long toReview,
      long dismissed,
      long logged,
      List<UploadView> uploads
  ) {
  }

  /** A recorded transaction for the review tabs, with any likely duplicate expense. */
  public record Listed(StatementTransaction transaction, Match match, String expenseTitle) {
  }

  // ---- uploading ---------------------------------------------------------------------------

  /** Reads a statement and returns its spending rows, marking those seen before. */
  public UploadResult upload(final ObjectId familyId, final byte[] bytes) {
    final Parent owner = access.requireMember(familyId);
    if (bytes == null || bytes.length == 0) {
      throw badRequest("Choose a statement to upload");
    }
    if (bytes.length > MAX_BYTES) {
      throw badRequest("A statement can be up to 2 MB. Export a shorter period.");
    }
    final StatementParser.ParsedStatement parsed;
    try {
      parsed = parser.parse(bytes);
    } catch (StatementParser.UnreadableStatementException unreadable) {
      throw badRequest(unreadable.getMessage());
    }
    final Map<String, StatementTransaction> known = known(owner, parsed.debits().stream()
        .map(StatementParser.ParsedTransaction::fingerprint).toList());
    final Set<ObjectId> live = liveExpenseIds(familyId);
    final List<Expense> familyExpenses = liveExpenses(familyId);
    final String account = parsed.account();
    final List<Row> rows = parsed.debits().stream()
        .sorted(Comparator.comparing(StatementParser.ParsedTransaction::date).reversed())
        .map(debit -> {
          final StatementTransaction seen = known.get(debit.fingerprint());
          final String state = seen == null ? null
              : StatementTransaction.LOGGED.equals(seen.status())
                  && !live.contains(seen.expenseId()) ? StatementTransaction.CHECKED
              : seen.status();
          return new Row(debit.fingerprint(), debit.date(), debit.description(),
              debit.details(), debit.amountPence(), account, state,
              seen == null ? null : seen.id(),
              StatementTransaction.LOGGED.equals(state) ? seen.expenseId() : null,
              StatementTransaction.LOGGED.equals(state) ? null
                  : match(familyExpenses, debit.amountPence(), debit.date()));
        })
        .toList();
    final int fresh = (int) rows.stream().filter(row -> row.state() == null).count();
    StatementUpload upload = null;
    if (fresh > 0) {
      final Instant now = clock.instant();
      upload = mongoTemplate.insert(new StatementUpload(new ObjectId(), familyId, owner.id(),
          parsed.format().name(), account, parsed.from(), parsed.to(), parsed.debits().size(),
          parsed.credits(), parsed.unreadable(), fresh, 0, 0, properties.aiEnabled(), now, now));
    }
    // Counts only: a merchant or amount in the audit trail would be the statement by another name.
    audits.record(familyId, "statement", upload == null ? owner.id() : upload.id(), "upload",
        Map.of("format", parsed.format().name(), "spending", parsed.debits().size(),
            "new", fresh));
    return new UploadResult(upload, parsed.format().name(), account, parsed.from(), parsed.to(),
        parsed.credits(), parsed.unreadable(), rows, properties.aiEnabled());
  }

  /**
   * Checks a batch of an upload's new rows and records each by fingerprint. A row already
   * recorded, by an earlier batch, another tab or an earlier upload, is left as it is and not
   * sent to the model again.
   */
  public List<CheckResult> check(final ObjectId familyId, final ObjectId uploadId,
      final List<RowInput> rows) {
    final Parent owner = access.requireMember(familyId);
    if (!properties.aiEnabled()) {
      throw conflict("Suggestions are switched off");
    }
    if (rows == null || rows.isEmpty() || rows.size() > StatementClassifier.MAX_BATCH) {
      throw badRequest("Send between 1 and " + StatementClassifier.MAX_BATCH + " transactions");
    }
    rows.forEach(StatementService::requireRow);
    final StatementUpload upload = mongoTemplate.findOne(Query.query(Criteria.where("_id")
        .is(uploadId).and("ownerParentId").is(owner.id()).and("familyId").is(familyId)),
        StatementUpload.class);
    if (upload == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload not found");
    }
    final Map<String, RowInput> unique = new LinkedHashMap<>();
    rows.forEach(row -> unique.putIfAbsent(row.fingerprint(), row));
    final Map<String, StatementTransaction> known = known(owner, List.copyOf(unique.keySet()));
    final List<RowInput> fresh = unique.values().stream()
        .filter(row -> !known.containsKey(row.fingerprint())).toList();
    final List<CheckResult> results = new ArrayList<>();
    known.values().forEach(seen ->
        results.add(new CheckResult(seen.fingerprint(), seen.status(), seen)));
    if (fresh.isEmpty()) {
      return results;
    }
    final List<StatementClassifier.Verdict> verdicts = classifier.classify(context(familyId,
        owner), fresh.stream().map(row -> new StatementClassifier.Item(row.date(),
        row.description(), row.details(), row.amountPence())).toList());
    final Instant now = clock.instant();
    int suggested = 0;
    for (int index = 0; index < fresh.size(); index++) {
      final RowInput row = fresh.get(index);
      final StatementClassifier.Verdict verdict = verdicts.get(index);
      final StatementTransaction recorded = record(owner, familyId, uploadId, row, verdict, now);
      if (StatementTransaction.SUGGESTED.equals(recorded.status())
          && uploadId.equals(recorded.uploadId())) {
        suggested++;
      }
      results.add(new CheckResult(row.fingerprint(), recorded.status(), recorded));
    }
    mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(uploadId)),
        new Update().inc("checkedCount", fresh.size()).inc("suggestedCount", suggested)
            .set("updatedAt", now), StatementUpload.class);
    return results;
  }

  /** Records a checked row unless it already is. The first record for a fingerprint wins. */
  private StatementTransaction record(final Parent owner, final ObjectId familyId,
      final ObjectId uploadId, final RowInput row, final StatementClassifier.Verdict verdict,
      final Instant now) {
    final boolean shared = verdict.shared();
    final StatementTransaction candidate = new StatementTransaction(new ObjectId(), familyId,
        owner.id(), row.fingerprint(),
        shared ? StatementTransaction.SUGGESTED : StatementTransaction.CHECKED,
        shared ? new StatementTransaction.Pending(row.date(), row.description(), row.details(),
            row.amountPence(), row.account(), new StatementTransaction.Suggestion(
                verdict.confidence(), verdict.title(), verdict.category(),
                verdict.childIds().stream().map(ObjectId::new).toList(), verdict.reason()))
            : null,
        null, null, null, uploadId, null, now, now);
    try {
      return mongoTemplate.insert(candidate);
    } catch (DuplicateKeyException alreadyRecorded) {
      return mongoTemplate.findOne(ownerFingerprint(owner, row.fingerprint()),
          StatementTransaction.class);
    }
  }

  // ---- reading -----------------------------------------------------------------------------

  /** Tab counts and the most recent uploads for the signed-in parent. */
  public Overview overview(final ObjectId familyId) {
    final Parent owner = access.requireMember(familyId);
    final Instant now = clock.instant();
    final List<UploadView> uploads = mongoTemplate.find(Query.query(Criteria.where("familyId")
            .is(familyId).and("ownerParentId").is(owner.id()))
            .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(MAX_UPLOADS_LISTED),
        StatementUpload.class).stream().map(upload -> new UploadView(upload,
            uploadStatus(upload, now))).toList();
    return new Overview(properties.aiEnabled(),
        count(owner, familyId, StatementTransaction.SUGGESTED),
        count(owner, familyId, StatementTransaction.DISMISSED),
        count(owner, familyId, StatementTransaction.LOGGED), uploads);
  }

  /** The recorded transactions in one tab: suggested, dismissed or logged. */
  public List<Listed> list(final ObjectId familyId, final String status) {
    final Parent owner = access.requireMember(familyId);
    if (!Set.of(StatementTransaction.SUGGESTED, StatementTransaction.DISMISSED,
        StatementTransaction.LOGGED).contains(status)) {
      throw badRequest("Choose suggested, dismissed or logged");
    }
    final List<StatementTransaction> found = mongoTemplate.find(Query.query(
            Criteria.where("familyId").is(familyId).and("ownerParentId").is(owner.id())
                .and("status").is(status))
            .with(Sort.by(Sort.Direction.DESC, StatementTransaction.SUGGESTED.equals(status)
                ? "pending.date" : "decidedAt")).limit(MAX_LISTED),
        StatementTransaction.class);
    final List<Expense> familyExpenses = liveExpenses(familyId);
    final Map<ObjectId, Expense> byId = familyExpenses.stream()
        .collect(Collectors.toMap(Expense::id, Function.identity()));
    return found.stream().map(transaction -> {
      final StatementTransaction.Pending pending = transaction.pending();
      final Expense logged = transaction.expenseId() == null ? null
          : byId.get(transaction.expenseId());
      return new Listed(transaction, pending == null ? null
          : match(familyExpenses, pending.amountPence(), pending.date()),
          logged == null ? null : logged.title());
    }).toList();
  }

  // ---- deciding ----------------------------------------------------------------------------

  /** The parent says a suggestion is not shared. Its details go; the merchant name stays. */
  public StatementTransaction dismiss(final ObjectId familyId, final ObjectId transactionId) {
    final Parent owner = access.requireMember(familyId);
    final StatementTransaction current = owned(owner, familyId, transactionId);
    if (!StatementTransaction.SUGGESTED.equals(current.status())) {
      throw conflict("Only a suggestion waiting for you can be marked as not shared");
    }
    final Instant now = clock.instant();
    final StatementTransaction saved = mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(current.id())
            .and("status").is(StatementTransaction.SUGGESTED)),
        new Update().set("status", StatementTransaction.DISMISSED)
            .set("merchant", current.pending().description())
            .unset("pending").set("decidedAt", now).set("updatedAt", now),
        FindAndModifyOptions.options().returnNew(true), StatementTransaction.class);
    if (saved == null) {
      throw conflict("This suggestion has changed. Reload and try again.");
    }
    return saved;
  }

  /**
   * Forgets a "not shared" decision, so the transaction is checked afresh the next time its
   * statement is uploaded.
   */
  public void forget(final ObjectId familyId, final ObjectId transactionId) {
    final Parent owner = access.requireMember(familyId);
    final StatementTransaction current = owned(owner, familyId, transactionId);
    if (!StatementTransaction.DISMISSED.equals(current.status())) {
      throw conflict("Only a transaction you marked as not shared can be forgotten");
    }
    mongoTemplate.remove(Query.query(Criteria.where("_id").is(current.id())
        .and("status").is(StatementTransaction.DISMISSED)), StatementTransaction.class);
  }

  /**
   * Turns a transaction into an expense: a waiting suggestion, or any row of an upload the page
   * is showing. It is always a paid expense, and the parent chooses who paid, since a joint
   * account's payments can belong to either parent.
   *
   * <p>The transaction is claimed before the expense is created, guarded on what it was, so two
   * clicks or two tabs log it once. If creating the expense fails, the claim is put back.
   */
  public Expense convert(final ObjectId familyId, final String fingerprint, final ObjectId uploadId,
      final RowInput row, final ExpenseService.ExpenseValues values) {
    final Parent owner = access.requireMember(familyId);
    if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
      throw badRequest("Malformed transaction");
    }
    final StatementTransaction prior = mongoTemplate.findOne(ownerFingerprint(owner, fingerprint),
        StatementTransaction.class);
    if (prior != null && !familyId.equals(prior.familyId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
    }
    if (prior != null && StatementTransaction.LOGGED.equals(prior.status())
        && prior.expenseId() != null && liveExpenseIds(familyId).contains(prior.expenseId())) {
      throw conflict("This transaction is already an expense");
    }
    final String merchant;
    if (prior != null && prior.pending() != null) {
      merchant = prior.pending().description();
    } else if (prior != null && prior.merchant() != null) {
      merchant = prior.merchant();
    } else {
      if (row == null || !fingerprint.equals(row.fingerprint())) {
        throw badRequest("Send the transaction you are logging");
      }
      requireRow(row);
      merchant = row.description();
    }
    final ExpenseService.ExpenseValues paid = new ExpenseService.ExpenseValues(values.title(),
        values.category(), values.childIds(), values.amountPence(), Expense.PAID, values.date(),
        values.payerId(), values.shares(), values.notes());
    final ObjectId expenseId = new ObjectId();
    final Instant now = clock.instant();
    claim(owner, familyId, fingerprint, uploadId, prior, merchant, values.category(), expenseId,
        now);
    try {
      final Expense expense = expenseService.create(familyId, paid, expenseId, null);
      audits.record(familyId, "statement", expenseId, "log", Map.of(
          "amountPence", expense.amountPence(), "category", expense.category()));
      return expense;
    } catch (RuntimeException failed) {
      release(owner, fingerprint, prior, expenseId);
      throw failed;
    }
  }

  private void claim(final Parent owner, final ObjectId familyId, final String fingerprint,
      final ObjectId uploadId, final StatementTransaction prior, final String merchant,
      final String category, final ObjectId expenseId, final Instant now) {
    if (prior == null) {
      try {
        mongoTemplate.insert(new StatementTransaction(new ObjectId(), familyId, owner.id(),
            fingerprint, StatementTransaction.LOGGED, null, merchant, category, expenseId,
            uploadId, now, now, now));
        return;
      } catch (DuplicateKeyException raced) {
        throw conflict("This transaction has just changed. Reload and try again.");
      }
    }
    final Criteria guard = Criteria.where("_id").is(prior.id()).and("status").is(prior.status());
    guard.and("expenseId").is(prior.expenseId());
    final StatementTransaction claimed = mongoTemplate.findAndModify(Query.query(guard),
        new Update().set("status", StatementTransaction.LOGGED).set("expenseId", expenseId)
            .set("merchant", merchant).set("category", category).unset("pending")
            .set("decidedAt", now).set("updatedAt", now),
        FindAndModifyOptions.options().returnNew(true), StatementTransaction.class);
    if (claimed == null) {
      throw conflict("This transaction has just changed. Reload and try again.");
    }
  }

  /** Puts back what a failed conversion claimed, only if nothing else has changed it since. */
  private void release(final Parent owner, final String fingerprint,
      final StatementTransaction prior, final ObjectId expenseId) {
    final Query claimed = Query.query(Criteria.where("ownerParentId").is(owner.id())
        .and("fingerprint").is(fingerprint).and("expenseId").is(expenseId));
    try {
      if (prior == null) {
        mongoTemplate.remove(claimed, StatementTransaction.class);
      } else {
        mongoTemplate.findAndReplace(claimed, prior, FindAndReplaceOptions.options());
      }
    } catch (RuntimeException releaseFailed) {
      // The expense was never created, so the worst left behind is a row that reads as logged
      // against an expense that does not exist, which converting again already treats as free.
      log.warn("Could not release statement transaction claim {}", expenseId, releaseFailed);
    }
  }

  // ---- helpers -----------------------------------------------------------------------------

  private StatementClassifier.Context context(final ObjectId familyId, final Parent owner) {
    final LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Europe/London")));
    final List<StatementClassifier.Child> kids = children.findByFamilyIdAndDeletedAtIsNull(familyId)
        .stream().map(child -> new StatementClassifier.Child(child.id().toHexString(),
            firstName(child), age(child, today), blankToNull(child.school()))).toList();
    final Map<String, StatementClassifier.Decision> decisions = new LinkedHashMap<>();
    mongoTemplate.find(Query.query(Criteria.where("familyId").is(familyId)
            .and("ownerParentId").is(owner.id())
            .and("status").in(StatementTransaction.DISMISSED, StatementTransaction.LOGGED)
            .and("merchant").ne(null))
            .with(Sort.by(Sort.Direction.DESC, "decidedAt")).limit(MAX_DECISIONS * 3),
        StatementTransaction.class).forEach(decided -> decisions.putIfAbsent(
            decided.merchant().toLowerCase(java.util.Locale.UK),
            new StatementClassifier.Decision(decided.merchant(),
                StatementTransaction.LOGGED.equals(decided.status()), decided.category())));
    // First names only: enough to tell a payment between the parents from a child's cost.
    final String other = parents.findByFamilyIdAndStatus(familyId, CoparentAccessPolicy.ACTIVE)
        .stream().filter(parent -> !parent.id().equals(owner.id())).findFirst()
        .or(() -> parents.findByFamilyIdAndStatus(familyId, CoparentAccessPolicy.INVITED)
            .stream().filter(parent -> !parent.id().equals(owner.id())).findFirst())
        .map(parent -> firstName(parent.fullName())).orElse(null);
    return new StatementClassifier.Context(today, firstName(owner.fullName()), other, kids,
        decisions.values().stream().limit(MAX_DECISIONS).toList());
  }

  private static String firstName(final Child child) {
    final String name = firstName(child.fullName());
    return name == null ? "Child" : name;
  }

  private static String firstName(final String fullName) {
    final String name = fullName == null ? "" : fullName.strip();
    return name.isEmpty() ? null : name.split("\\s+")[0];
  }

  private static Integer age(final Child child, final LocalDate today) {
    return child.dateOfBirth() == null || child.dateOfBirth().isAfter(today) ? null
        : Period.between(child.dateOfBirth(), today).getYears();
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private Map<String, StatementTransaction> known(final Parent owner,
      final List<String> fingerprints) {
    final Map<String, StatementTransaction> known = new HashMap<>();
    if (fingerprints.isEmpty()) {
      return known;
    }
    mongoTemplate.find(Query.query(Criteria.where("ownerParentId").is(owner.id())
            .and("fingerprint").in(fingerprints)), StatementTransaction.class)
        .forEach(transaction -> known.put(transaction.fingerprint(), transaction));
    return known;
  }

  private List<Expense> liveExpenses(final ObjectId familyId) {
    return expenses.findByFamilyIdAndDeletedAtIsNullOrderByDateDescCreatedAtDesc(familyId);
  }

  private Set<ObjectId> liveExpenseIds(final ObjectId familyId) {
    return liveExpenses(familyId).stream().map(Expense::id).collect(Collectors.toSet());
  }

  /**
   * The family's expense this payment most likely already is. Family-wide on purpose: both
   * parents can upload a joint account's statement, and either may already have logged it.
   */
  static Match match(final List<Expense> expenses, final long amountPence, final LocalDate date) {
    return expenses.stream()
        .filter(expense -> expense.amountPence() == amountPence && expense.date() != null
            && Math.abs(ChronoUnit.DAYS.between(expense.date(), date)) <= MATCH_DAYS)
        .min(Comparator.comparingLong(expense ->
            Math.abs(ChronoUnit.DAYS.between(expense.date(), date))))
        .map(expense -> new Match(expense.id(), expense.title(), expense.date()))
        .orElse(null);
  }

  private String uploadStatus(final StatementUpload upload, final Instant now) {
    if (upload.checkedCount() >= upload.newCount()) {
      return "done";
    }
    if (!upload.aiEnabled()) {
      return "off";
    }
    return upload.updatedAt().isAfter(now.minus(CHECKING_GRACE)) ? "checking" : "incomplete";
  }

  private long count(final Parent owner, final ObjectId familyId, final String status) {
    return mongoTemplate.count(Query.query(Criteria.where("familyId").is(familyId)
        .and("ownerParentId").is(owner.id()).and("status").is(status)),
        StatementTransaction.class);
  }

  private StatementTransaction owned(final Parent owner, final ObjectId familyId,
      final ObjectId transactionId) {
    final StatementTransaction found = mongoTemplate.findOne(Query.query(Criteria.where("_id")
            .is(transactionId).and("familyId").is(familyId).and("ownerParentId").is(owner.id())),
        StatementTransaction.class);
    if (found == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
    }
    return found;
  }

  private static Query ownerFingerprint(final Parent owner, final String fingerprint) {
    return Query.query(Criteria.where("ownerParentId").is(owner.id())
        .and("fingerprint").is(fingerprint));
  }

  private static void requireRow(final RowInput row) {
    if (row == null || row.fingerprint() == null
        || !FINGERPRINT.matcher(row.fingerprint()).matches()
        || row.date() == null || row.description() == null || row.description().isBlank()
        || row.description().length() > StatementParser.MAX_DESCRIPTION
        || row.details() != null && row.details().length() > StatementParser.MAX_DETAILS
        || row.account() != null && row.account().length() > 80
        || row.amountPence() < 1 || row.amountPence() > ExpenseService.MAX_AMOUNT_PENCE) {
      throw badRequest("A transaction was malformed. Upload the statement again.");
    }
  }

  private static ResponseStatusException badRequest(final String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(final String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
