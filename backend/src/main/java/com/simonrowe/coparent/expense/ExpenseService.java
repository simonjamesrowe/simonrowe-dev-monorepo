package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.model.Family;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.ExpenseRepository;
import com.simonrowe.coparent.persistence.FamilyRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared expenses: logging, agreeing, paying back and settling up.
 *
 * <p>One rule drives the agreement state: whoever last changed the terms (amount, share, or a
 * payer once one is decided) has agreed to them, and the other parent must respond. Every write
 * reads the expense, applies the rules here, and replaces it guarded on its {@code version}, so a
 * concurrent change always loses with a 409 rather than overwriting the other parent's.
 */
@Service
public class ExpenseService {

  public static final Set<String> CATEGORIES = Set.of(
      "education", "clothing", "medical", "activities", "childcare", "travel", "food", "other");
  public static final long MAX_AMOUNT_PENCE = 10_000_000;
  static final int MAX_TITLE = 120;
  static final int MAX_NOTES = 1000;
  static final int MAX_NOTE = 500;
  static final int MAX_HISTORY = 50;
  static final int MAX_SETTLE_ITEMS = 50;
  private static final int DATE_WINDOW_YEARS = 2;
  private static final String ACTIVE = CoparentAccessPolicy.ACTIVE;
  private static final String STALE = "This expense has changed. Reload it and try again.";

  private final ExpenseRepository expenses;
  private final ParentRepository parents;
  private final ChildRepository children;
  private final FamilyRepository families;
  private final CoparentAccessPolicy access;
  private final CoparentAuditService audits;
  private final ExpenseMailer mailer;
  private final MongoTemplate mongoTemplate;
  private final Clock clock;

  /** The terms and descriptive fields a parent can set on an expense. */
  public record ExpenseValues(
      String title,
      String category,
      List<ObjectId> childIds,
      long amountPence,
      String timing,
      LocalDate date,
      ObjectId payerId,
      List<Expense.Share> shares,
      String notes
  ) {
  }

  /** One expense to settle, at the version the parent was looking at. */
  public record SettleItem(ObjectId id, long version) {
  }

  /** What settling one expense did. {@code expense} is null when it could not be settled. */
  public record SettleResult(ObjectId id, String outcome, Expense expense, String message) {
  }

  @Autowired
  public ExpenseService(
      final ExpenseRepository expenses,
      final ParentRepository parents,
      final ChildRepository children,
      final FamilyRepository families,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      final ExpenseMailer mailer,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this(expenses, parents, children, families, access, audits, mailer, mongoTemplate,
        Clock.systemUTC());
  }

  ExpenseService(
      final ExpenseRepository expenses,
      final ParentRepository parents,
      final ChildRepository children,
      final FamilyRepository families,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      final ExpenseMailer mailer,
      final MongoTemplate mongoTemplate,
      final Clock clock) {
    this.expenses = expenses;
    this.parents = parents;
    this.children = children;
    this.families = families;
    this.access = access;
    this.audits = audits;
    this.mailer = mailer;
    this.mongoTemplate = mongoTemplate;
    this.clock = clock;
  }

  /** Every live expense in the family, newest first. */
  public List<Expense> list(final ObjectId familyId) {
    access.requireMember(familyId);
    return expenses.findByFamilyIdAndDeletedAtIsNullOrderByDateDescCreatedAtDesc(familyId);
  }

  /** One live expense in the family. */
  public Expense get(final ObjectId familyId, final ObjectId expenseId) {
    access.requireMember(familyId);
    return find(familyId, expenseId);
  }

  /** The balance and to-do counts from the signed-in parent's point of view. */
  public ExpenseSummary summary(final ObjectId familyId) {
    final Parent actor = access.requireMember(familyId);
    final LocalDate today = LocalDate.now(clock.withZone(zone(familyId)));
    return ExpenseSummary.of(
        expenses.findByFamilyIdAndDeletedAtIsNullOrderByDateDescCreatedAtDesc(familyId),
        actor.id(), today);
  }

  /**
   * Both parents' shares when the caller states only their own percentage, as the assistant
   * does: the other parent takes the rest.
   */
  public List<Expense.Share> callerShares(final ObjectId familyId, final int callerPercent) {
    final Couple couple = couple(familyId);
    return List.of(new Expense.Share(couple.actor().id(), callerPercent),
        new Expense.Share(couple.other().id(), 100 - callerPercent));
  }

  /** Logs an expense. The other parent is asked to agree to it. */
  public Expense create(final ObjectId familyId, final ExpenseValues values) {
    return create(familyId, values, null, null);
  }

  /** Logs an expense with preallocated identifiers, so an assistant retry adds nothing. */
  public Expense create(
      final ObjectId familyId,
      final ExpenseValues values,
      final ObjectId expenseId,
      final ObjectId assistantActionId) {
    final Couple couple = couple(familyId);
    final ExpenseValues checked = validate(familyId, values, couple);
    final Instant now = clock.instant();
    final Expense saved = expenses.insert(new Expense(
        expenseId == null ? new ObjectId() : expenseId, familyId, checked.title(),
        checked.category(), checked.childIds(), checked.amountPence(), Expense.GBP,
        checked.timing(), checked.date(), checked.payerId(), checked.shares(),
        pendingFrom(couple.actor()), Expense.Reimbursement.none(), List.of(), checked.notes(),
        List.of(entry(now, couple.actor(), "create", null)), 1, couple.actor().id(),
        assistantActionId, null, now, now));
    audit(saved, "create");
    mailer.needsAgreement(couple.other(), couple.actor(), saved);
    return saved;
  }

  /**
   * Replaces an expense's fields. Changing the terms asks the other parent to agree again, and
   * resending a disputed expense does too. An expense being paid back can no longer change.
   */
  public Expense update(
      final ObjectId familyId,
      final ObjectId expenseId,
      final long version,
      final ExpenseValues values) {
    final Couple couple = couple(familyId);
    final Expense current = current(familyId, expenseId, version);
    if (Expense.CLAIMED.equals(current.reimbursement().status())
        || Expense.REIMBURSED.equals(current.reimbursement().status())) {
      throw conflict("This expense is being paid back and can no longer change");
    }
    final ExpenseValues checked = validate(familyId, values, couple);
    final boolean termsChanged = current.amountPence() != checked.amountPence()
        || !current.timing().equals(checked.timing())
        || !sameShares(current.shares(), checked.shares())
        || current.payerId() != null && !current.payerId().equals(checked.payerId());
    final boolean resend = Expense.DISPUTED.equals(current.agreement().status())
        && couple.actor().id().equals(current.agreement().requestedBy());
    final boolean reask = termsChanged || resend;
    final Instant now = clock.instant();
    final Expense next = new Expense(current.id(), familyId, checked.title(), checked.category(),
        checked.childIds(), checked.amountPence(), Expense.GBP, checked.timing(), checked.date(),
        checked.payerId(), checked.shares(),
        reask ? pendingFrom(couple.actor()) : current.agreement(),
        reask ? Expense.Reimbursement.none() : current.reimbursement(), current.receipts(),
        checked.notes(), history(current, entry(now, couple.actor(), "update", null)),
        current.version() + 1, current.createdBy(), current.assistantActionId(), null,
        current.createdAt(), now);
    final Expense saved = replace(current, next);
    audit(saved, "update");
    if (reask) {
      mailer.needsAgreement(couple.other(), couple.actor(), saved);
    }
    return saved;
  }

  /**
   * Records that an upcoming expense has been paid. At the same amount, by the agreed payer or
   * with the payer previously undecided, it stays agreed; anything else is new terms.
   */
  public Expense markPaid(
      final ObjectId familyId,
      final ObjectId expenseId,
      final long version,
      final ObjectId payerId,
      final LocalDate paidOn,
      final long amountPence) {
    final Couple couple = couple(familyId);
    final Expense current = current(familyId, expenseId, version);
    if (!Expense.UPCOMING.equals(current.timing())) {
      throw conflict("Only an upcoming expense can be marked as paid");
    }
    requireParent(couple, payerId);
    requireAmount(amountPence);
    requirePaidDate(familyId, paidOn);
    final boolean termsChanged = amountPence != current.amountPence()
        || current.payerId() != null && !current.payerId().equals(payerId);
    final boolean reask = termsChanged || !Expense.AGREED.equals(current.agreement().status());
    final Instant now = clock.instant();
    final Expense paid = new Expense(current.id(), familyId, current.title(), current.category(),
        current.childIds(), amountPence, Expense.GBP, Expense.PAID, paidOn, payerId,
        current.shares(), reask ? pendingFrom(couple.actor()) : current.agreement(),
        Expense.Reimbursement.none(), current.receipts(), current.notes(),
        history(current, entry(now, couple.actor(), "mark_paid", null)), current.version() + 1,
        current.createdBy(), current.assistantActionId(), null, current.createdAt(), now);
    final Expense next = reask ? paid : withReimbursement(paid,
        new Expense.Reimbursement(ExpenseMath.reimbursementOnceAgreed(paid), null, null, null,
            null, null));
    final Expense saved = replace(current, next);
    audit(saved, "mark_paid");
    if (reask) {
      mailer.needsAgreement(couple.other(), couple.actor(), saved);
    }
    return saved;
  }

  /** The other parent accepts the current terms, and the expense starts to count. */
  public Expense agree(final ObjectId familyId, final ObjectId expenseId, final long version) {
    final Couple couple = couple(familyId);
    final Expense current = current(familyId, expenseId, version);
    requireResponder(current, couple.actor());
    final Instant now = clock.instant();
    final Expense agreed = transition(current, new Expense.Agreement(Expense.AGREED,
        current.agreement().requestedBy(), couple.actor().id(), now, null),
        current.reimbursement(), entry(now, couple.actor(), "agree", null), now);
    final Expense saved = replace(current, withReimbursement(agreed, new Expense.Reimbursement(
        ExpenseMath.reimbursementOnceAgreed(agreed), null, null, null, null, null)));
    audit(saved, "agree");
    return saved;
  }

  /** The other parent rejects the current terms, with a reason the requester will see. */
  public Expense dispute(
      final ObjectId familyId,
      final ObjectId expenseId,
      final long version,
      final String note) {
    final String reason = requireNote(note, "Say why you are disputing it");
    final Couple couple = couple(familyId);
    final Expense current = current(familyId, expenseId, version);
    requireResponder(current, couple.actor());
    final Instant now = clock.instant();
    final Expense saved = replace(current, transition(current, new Expense.Agreement(
        Expense.DISPUTED, current.agreement().requestedBy(), couple.actor().id(), now, reason),
        Expense.Reimbursement.none(), entry(now, couple.actor(), "dispute", reason), now));
    audit(saved, "dispute");
    mailer.disputed(couple.other(), couple.actor(), saved);
    return saved;
  }

  /** The parent who owes says they have paid it back. The payer confirms. */
  public Expense claim(
      final ObjectId familyId,
      final ObjectId expenseId,
      final long version,
      final String note) {
    final String method = optionalNote(note);
    final Couple couple = couple(familyId);
    final Expense current = current(familyId, expenseId, version);
    requireOutstanding(current);
    if (!couple.actor().id().equals(ExpenseMath.debtor(current))) {
      throw forbidden("Only the parent who owes can mark this as paid back");
    }
    final Instant now = clock.instant();
    final Expense saved = replace(current, transition(current, current.agreement(),
        new Expense.Reimbursement(Expense.CLAIMED, couple.actor().id(), now, null, null, method),
        entry(now, couple.actor(), "claim", method), now));
    audit(saved, "claim");
    mailer.claimed(couple.other(), couple.actor(), saved);
    return saved;
  }

  /** The payer confirms the money arrived. The expense is settled. */
  public Expense confirm(final ObjectId familyId, final ObjectId expenseId, final long version) {
    final Couple couple = couple(familyId);
    final Expense current = current(familyId, expenseId, version);
    requireClaimed(current);
    requirePayer(current, couple.actor(), "Only the parent who paid can confirm it was received");
    final Instant now = clock.instant();
    final Expense saved = replace(current, transition(current, current.agreement(),
        settled(current, couple.actor(), now), entry(now, couple.actor(), "confirm", null), now));
    audit(saved, "confirm");
    return saved;
  }

  /** The payer says the money has not arrived. It is outstanding again. */
  public Expense reject(
      final ObjectId familyId,
      final ObjectId expenseId,
      final long version,
      final String note) {
    final String reason = requireNote(note, "Say what is missing");
    final Couple couple = couple(familyId);
    final Expense current = current(familyId, expenseId, version);
    requireClaimed(current);
    requirePayer(current, couple.actor(), "Only the parent who paid can say it has not arrived");
    final Instant now = clock.instant();
    final Expense saved = replace(current, transition(current, current.agreement(),
        new Expense.Reimbursement(Expense.OUTSTANDING, null, null, null, null, reason),
        entry(now, couple.actor(), "reject_claim", reason), now));
    audit(saved, "reject_claim");
    return saved;
  }

  /** The payer settles an outstanding expense in one step, for example after being paid cash. */
  public Expense markReimbursed(
      final ObjectId familyId,
      final ObjectId expenseId,
      final long version) {
    final Couple couple = couple(familyId);
    final Expense current = current(familyId, expenseId, version);
    requireOutstanding(current);
    requirePayer(current, couple.actor(), "Only the parent who paid can mark it as reimbursed");
    final Instant now = clock.instant();
    final Expense saved = replace(current, transition(current, current.agreement(),
        settled(current, couple.actor(), now),
        entry(now, couple.actor(), "mark_reimbursed", null), now));
    audit(saved, "mark_reimbursed");
    return saved;
  }

  /**
   * Settles several expenses in one go, each one individually: an outstanding debt the caller
   * owes is marked as paid back, one owed to the caller is marked reimbursed, and a repayment
   * already claimed to the caller is confirmed. There are no transactions, so each item reports
   * its own outcome and one stale item never stops the rest.
   */
  public List<SettleResult> settle(final ObjectId familyId, final List<SettleItem> items) {
    if (items == null || items.isEmpty() || items.size() > MAX_SETTLE_ITEMS) {
      throw badRequest("Choose between 1 and " + MAX_SETTLE_ITEMS + " expenses to settle");
    }
    final Parent actor = access.requireMember(familyId);
    final List<SettleResult> results = new ArrayList<>();
    for (final SettleItem item : items) {
      try {
        final Expense current = find(familyId, item.id());
        if (current.version() != item.version()) {
          results.add(new SettleResult(item.id(), "failed", null, STALE));
          continue;
        }
        final boolean payer = actor.id().equals(current.payerId());
        if (Expense.CLAIMED.equals(current.reimbursement().status()) && payer) {
          results.add(new SettleResult(item.id(), "confirmed",
              confirm(familyId, item.id(), item.version()), null));
        } else if (Expense.OUTSTANDING.equals(current.reimbursement().status()) && payer) {
          results.add(new SettleResult(item.id(), "reimbursed",
              markReimbursed(familyId, item.id(), item.version()), null));
        } else if (Expense.OUTSTANDING.equals(current.reimbursement().status())) {
          results.add(new SettleResult(item.id(), "claimed",
              claim(familyId, item.id(), item.version(), "Settle up"), null));
        } else {
          results.add(new SettleResult(item.id(), "skipped", null,
              "Nothing to settle on this expense"));
        }
      } catch (ResponseStatusException exception) {
        results.add(new SettleResult(item.id(), "failed", null, exception.getReason()));
      }
    }
    return results;
  }

  /**
   * Removes an expense. An upcoming one can go at any time, since no money has moved. A paid
   * one only while it is still waiting for agreement, and only by the parent who sent it: an
   * agreed debt must not vanish from one side, so it is edited instead.
   */
  public void delete(final ObjectId familyId, final ObjectId expenseId, final long version) {
    final Couple couple = couple(familyId);
    final Expense current = current(familyId, expenseId, version);
    if (!Expense.UPCOMING.equals(current.timing())) {
      if (Expense.AGREED.equals(current.agreement().status())) {
        throw conflict("An agreed expense can't be deleted. Edit it instead.");
      }
      if (!couple.actor().id().equals(current.agreement().requestedBy())) {
        throw forbidden("Only the parent who sent this expense can withdraw it");
      }
    }
    final Instant now = clock.instant();
    replace(current, new Expense(current.id(), familyId, current.title(), current.category(),
        current.childIds(), current.amountPence(), current.currency(), current.timing(),
        current.date(), current.payerId(), current.shares(), current.agreement(),
        current.reimbursement(), current.receipts(), current.notes(),
        history(current, entry(now, couple.actor(), "delete", null)), current.version() + 1,
        current.createdBy(), current.assistantActionId(), now, current.createdAt(), now));
    audits.record(familyId, "expense", expenseId, "delete", Map.of());
  }

  // ---- rules -------------------------------------------------------------------------------

  private ExpenseValues validate(
      final ObjectId familyId,
      final ExpenseValues values,
      final Couple couple) {
    final String title = values.title() == null ? "" : values.title().trim();
    if (title.isEmpty() || title.length() > MAX_TITLE) {
      throw badRequest("Say what the expense was for, in up to " + MAX_TITLE + " characters");
    }
    if (values.category() == null || !CATEGORIES.contains(values.category())) {
      throw badRequest("Choose a category");
    }
    final List<ObjectId> childIds = values.childIds() == null ? List.of()
        : values.childIds().stream().distinct().toList();
    if (childIds.isEmpty()
        || children.countByIdInAndFamilyIdAndDeletedAtIsNull(childIds, familyId)
        != childIds.size()) {
      throw badRequest("Choose at least one child in this family");
    }
    requireAmount(values.amountPence());
    if (!Expense.PAID.equals(values.timing()) && !Expense.UPCOMING.equals(values.timing())) {
      throw badRequest("Say whether it has been paid or is coming up");
    }
    if (values.date() == null) {
      throw badRequest("A date is required");
    }
    if (Expense.PAID.equals(values.timing())) {
      if (values.payerId() == null) {
        throw badRequest("Say who paid");
      }
      requirePaidDate(familyId, values.date());
    } else {
      requireWithinWindow(familyId, values.date());
    }
    if (values.payerId() != null) {
      requireParent(couple, values.payerId());
    }
    final String notes = values.notes() == null || values.notes().isBlank()
        ? null : values.notes().trim();
    if (notes != null && notes.length() > MAX_NOTES) {
      throw badRequest("Notes can be up to " + MAX_NOTES + " characters");
    }
    return new ExpenseValues(title, values.category(), childIds, values.amountPence(),
        values.timing(), values.date(), values.payerId(), shares(values.shares(), couple), notes);
  }

  /** Exactly the family's two parents, whole percentages summing to 100, in a fixed order. */
  private static List<Expense.Share> shares(final List<Expense.Share> shares, final Couple couple) {
    final Set<ObjectId> expected = Set.of(couple.actor().id(), couple.other().id());
    if (shares == null || shares.size() != 2
        || !new HashSet<>(shares.stream().map(Expense.Share::parentId).toList()).equals(expected)
        || shares.stream().anyMatch(share -> share.percent() < 0 || share.percent() > 100)
        || shares.stream().mapToInt(Expense.Share::percent).sum() != 100) {
      throw badRequest("Share the cost between both parents, in percentages that add up to 100");
    }
    return shares.stream().sorted(Comparator.comparing(share -> share.parentId().toHexString()))
        .toList();
  }

  private static boolean sameShares(final List<Expense.Share> left,
      final List<Expense.Share> right) {
    return Set.copyOf(left).equals(Set.copyOf(right));
  }

  private static void requireAmount(final long amountPence) {
    if (amountPence < 1 || amountPence > MAX_AMOUNT_PENCE) {
      throw badRequest("Enter an amount between £0.01 and %s"
          .formatted(ExpenseMoney.format(MAX_AMOUNT_PENCE)));
    }
  }

  private void requirePaidDate(final ObjectId familyId, final LocalDate date) {
    if (date == null) {
      throw badRequest("Say when it was paid");
    }
    if (date.isAfter(LocalDate.now(clock.withZone(zone(familyId))))) {
      throw badRequest("A paid expense can't be dated in the future");
    }
    requireWithinWindow(familyId, date);
  }

  private void requireWithinWindow(final ObjectId familyId, final LocalDate date) {
    final LocalDate today = LocalDate.now(clock.withZone(zone(familyId)));
    if (date.isBefore(today.minusYears(DATE_WINDOW_YEARS))
        || date.isAfter(today.plusYears(DATE_WINDOW_YEARS))) {
      throw badRequest("Choose a date within " + DATE_WINDOW_YEARS + " years of today");
    }
  }

  private static void requireParent(final Couple couple, final ObjectId parentId) {
    if (parentId == null
        || !parentId.equals(couple.actor().id()) && !parentId.equals(couple.other().id())) {
      throw badRequest("The payer must be one of the family's parents");
    }
  }

  private static void requireResponder(final Expense expense, final Parent actor) {
    if (!Expense.PENDING.equals(expense.agreement().status())) {
      throw conflict("This expense is no longer waiting for agreement");
    }
    if (actor.id().equals(expense.agreement().requestedBy())) {
      throw forbidden("The other parent needs to agree to this, not you");
    }
  }

  private static void requireOutstanding(final Expense expense) {
    if (!ExpenseMath.countsTowardsBalance(expense)
        || !Expense.OUTSTANDING.equals(expense.reimbursement().status())) {
      throw conflict("Only an agreed, outstanding expense can be paid back");
    }
  }

  private static void requireClaimed(final Expense expense) {
    if (!Expense.CLAIMED.equals(expense.reimbursement().status())) {
      throw conflict("Nobody has marked this as paid back yet");
    }
  }

  private static void requirePayer(final Expense expense, final Parent actor,
      final String message) {
    if (!actor.id().equals(expense.payerId())) {
      throw forbidden(message);
    }
  }

  private static String requireNote(final String note, final String message) {
    final String trimmed = note == null ? "" : note.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_NOTE) {
      throw badRequest(message + ", in up to " + MAX_NOTE + " characters");
    }
    return trimmed;
  }

  private static String optionalNote(final String note) {
    if (note == null || note.isBlank()) {
      return null;
    }
    final String trimmed = note.trim();
    if (trimmed.length() > MAX_NOTE) {
      throw badRequest("Notes can be up to " + MAX_NOTE + " characters");
    }
    return trimmed;
  }

  // ---- persistence -------------------------------------------------------------------------

  private record Couple(Parent actor, Parent other) {
  }

  /** The caller and the family's other parent. Shared expenses need exactly two. */
  private Couple couple(final ObjectId familyId) {
    final Parent actor = access.requireMember(familyId);
    final List<Parent> others = parents.findByFamilyIdAndStatus(familyId, ACTIVE).stream()
        .filter(parent -> !parent.id().equals(actor.id())).toList();
    if (others.isEmpty()) {
      throw conflict("Invite your co-parent before adding shared expenses");
    }
    if (others.size() > 1) {
      throw conflict("Shared expenses need exactly two parents in the family");
    }
    return new Couple(actor, others.getFirst());
  }

  private Expense find(final ObjectId familyId, final ObjectId expenseId) {
    return expenses.findByIdAndFamilyIdAndDeletedAtIsNull(expenseId, familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Expense not found"));
  }

  /** Loads the expense and checks the caller was looking at its current version. */
  private Expense current(final ObjectId familyId, final ObjectId expenseId, final long version) {
    final Expense current = find(familyId, expenseId);
    if (current.version() != version) {
      throw conflict(STALE);
    }
    return current;
  }

  /** Writes {@code next} only if nobody else has written since {@code current} was read. */
  private Expense replace(final Expense current, final Expense next) {
    final Expense saved = mongoTemplate.findAndReplace(
        Query.query(Criteria.where("_id").is(current.id())
            .and("familyId").is(current.familyId())
            .and("deletedAt").is(null)
            .and("version").is(current.version())),
        next, FindAndReplaceOptions.options().returnNew());
    if (saved == null) {
      throw conflict(STALE);
    }
    return saved;
  }

  private ZoneId zone(final ObjectId familyId) {
    final String zone = families.findByIdAndDeletedAtIsNull(familyId)
        .map(Family::timeZone).orElse(null);
    try {
      return zone == null || zone.isBlank() ? ZoneId.of("Europe/London") : ZoneId.of(zone);
    } catch (java.time.DateTimeException invalid) {
      return ZoneId.of("Europe/London");
    }
  }

  private void audit(final Expense expense, final String action) {
    // Amounts and states only: no title, notes or receipt names, which can be personal.
    audits.record(expense.familyId(), "expense", expense.id(), action, Map.of(
        "amountPence", expense.amountPence(),
        "currency", expense.currency(),
        "timing", expense.timing(),
        "category", expense.category(),
        "agreement", expense.agreement().status(),
        "reimbursement", expense.reimbursement().status()));
  }

  // ---- building records --------------------------------------------------------------------

  private static Expense.Agreement pendingFrom(final Parent requester) {
    return new Expense.Agreement(Expense.PENDING, requester.id(), null, null, null);
  }

  private static Expense.Reimbursement settled(final Expense current, final Parent actor,
      final Instant now) {
    final Expense.Reimbursement before = current.reimbursement();
    return new Expense.Reimbursement(Expense.REIMBURSED, before.claimedBy(), before.claimedAt(),
        actor.id(), now, before.note());
  }

  private static Expense.HistoryEntry entry(final Instant at, final Parent by,
      final String action, final String note) {
    return new Expense.HistoryEntry(at, by.id(), action, note);
  }

  /** Appends to the expense's timeline, keeping only the most recent entries. */
  private static List<Expense.HistoryEntry> history(final Expense current,
      final Expense.HistoryEntry entry) {
    final List<Expense.HistoryEntry> all = new ArrayList<>(current.history());
    all.add(entry);
    return all.size() <= MAX_HISTORY ? all : all.subList(all.size() - MAX_HISTORY, all.size());
  }

  private static Expense transition(final Expense current, final Expense.Agreement agreement,
      final Expense.Reimbursement reimbursement, final Expense.HistoryEntry entry,
      final Instant now) {
    return new Expense(current.id(), current.familyId(), current.title(), current.category(),
        current.childIds(), current.amountPence(), current.currency(), current.timing(),
        current.date(), current.payerId(), current.shares(), agreement, reimbursement,
        current.receipts(), current.notes(), history(current, entry), current.version() + 1,
        current.createdBy(), current.assistantActionId(), current.deletedAt(),
        current.createdAt(), now);
  }

  private static Expense withReimbursement(final Expense expense,
      final Expense.Reimbursement reimbursement) {
    return new Expense(expense.id(), expense.familyId(), expense.title(), expense.category(),
        expense.childIds(), expense.amountPence(), expense.currency(), expense.timing(),
        expense.date(), expense.payerId(), expense.shares(), expense.agreement(), reimbursement,
        expense.receipts(), expense.notes(), expense.history(), expense.version(),
        expense.createdBy(), expense.assistantActionId(), expense.deletedAt(),
        expense.createdAt(), expense.updatedAt());
  }

  private static ResponseStatusException badRequest(final String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(final String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException forbidden(final String message) {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
  }
}
