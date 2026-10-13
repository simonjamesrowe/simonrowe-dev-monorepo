package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.model.ExpenseSettlement;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.ExpenseRepository;
import com.simonrowe.coparent.persistence.ExpenseSettlementRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Settling up as the money actually moved: one or more payments, such as bank transfers, and the
 * expenses they paid back.
 *
 * <p>The payments are recorded as sent, even when they do not add up to what the chosen expenses
 * net to: people send round numbers. The expenses are settled either way, and the difference is
 * carried into the balance ({@link ExpenseSummary}), so an overpayment is owed back and a
 * shortfall is still owed. That is what keeps the balance honest without making a parent
 * pretend they sent the exact figure.
 */
@Service
public class ExpenseSettlementService {

  static final int MAX_PAYMENTS = 10;
  static final int MAX_REFERENCE = 100;
  private static final int DATE_WINDOW_YEARS = 2;
  private static final Set<String> METHODS = Set.of(
      ExpenseSettlement.BANK_TRANSFER, ExpenseSettlement.CASH, ExpenseSettlement.OTHER);

  private final ExpenseService expenseService;
  private final ExpenseSettlementRepository settlements;
  private final ExpenseRepository expenses;
  private final CoparentAccessPolicy access;
  private final CoparentAuditService audits;
  private final ExpenseMailer mailer;
  private final Clock clock;

  /** One payment as the parent entered it. */
  public record PaymentValues(String method, long amountPence, LocalDate paidOn,
      String reference) {
  }

  /** The settle-up that was recorded, or null when no expense was settled, and each outcome. */
  public record Recorded(ExpenseSettlement settlement, List<ExpenseService.SettleResult> results) {
  }

  @Autowired
  public ExpenseSettlementService(
      final ExpenseService expenseService,
      final ExpenseSettlementRepository settlements,
      final ExpenseRepository expenses,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      final ExpenseMailer mailer) {
    this(expenseService, settlements, expenses, access, audits, mailer, Clock.systemUTC());
  }

  ExpenseSettlementService(
      final ExpenseService expenseService,
      final ExpenseSettlementRepository settlements,
      final ExpenseRepository expenses,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      final ExpenseMailer mailer,
      final Clock clock) {
    this.expenseService = expenseService;
    this.settlements = settlements;
    this.expenses = expenses;
    this.access = access;
    this.audits = audits;
    this.mailer = mailer;
    this.clock = clock;
  }

  /** Every settle-up in the family, newest first. */
  public List<ExpenseSettlement> list(final ObjectId familyId) {
    access.requireMember(familyId);
    return settlements.findByFamilyIdOrderByCreatedAtDesc(familyId);
  }

  /**
   * Settles the chosen expenses and records the payments that settled them. Every expense is
   * checked first, and the payments against their net, so a refusal writes nothing.
   */
  public Recorded record(
      final ObjectId familyId,
      final List<ExpenseService.SettleItem> items,
      final List<PaymentValues> payments) {
    final ExpenseService.SettlePlan plan = expenseService.planSettlement(familyId, items);
    final long net = plan.items().stream()
        .mapToLong(ExpenseService.PlannedSettle::netToActor).sum();
    final boolean moves = plan.items().stream()
        .anyMatch(item -> item.step() != ExpenseService.SettleStep.CONFIRM);
    if (!moves) {
      // Only confirmations: the money moved when the other parent recorded paying it, and
      // their settle-up already holds those payments.
      if (payments != null && !payments.isEmpty()) {
        throw badRequest("These were already paid back, so there is no payment to record");
      }
      return new Recorded(null, expenseService.applySettlement(familyId, plan, null, null));
    }
    final List<ExpenseSettlement.Payment> checked = payments(familyId, payments, net != 0);

    final ObjectId settlementId = new ObjectId();
    final List<ExpenseService.SettleResult> results = expenseService.applySettlement(familyId,
        plan, settlementId, methodNote(checked));
    final List<ObjectId> linked = results.stream()
        .filter(result -> result.expense() != null
            && settlementId.equals(result.expense().reimbursement().settlementId()))
        .map(ExpenseService.SettleResult::id)
        .toList();
    if (linked.isEmpty()) {
      return new Recorded(null, results);
    }
    // Recorded after the expenses, and naming only the ones that settled. An expense can only
    // fail here if it changed in the moment between the check and the write; the payments then
    // cover more than the expenses listed, and the response says which one failed.
    final Parent actor = plan.actor();
    final Parent other = plan.other();
    final ExpenseSettlement saved = settlements.insert(new ExpenseSettlement(settlementId,
        familyId, net < 0 ? actor.id() : net > 0 ? other.id() : null,
        net < 0 ? other.id() : net > 0 ? actor.id() : null, Math.abs(net), Expense.GBP, checked,
        linked, actor.id(), clock.instant(), null, null));
    // Amounts only: references can name people or accounts.
    audits.record(familyId, "expense_settlement", settlementId, "record", Map.of(
        "amountPence", saved.amountPence(),
        "paidPence", saved.paidPence(),
        "currency", saved.currency(),
        "payments", saved.payments().size(),
        "expenses", saved.expenseIds().size()));
    final List<Expense> claimed = results.stream()
        .filter(result -> "claimed".equals(result.outcome()))
        .map(ExpenseService.SettleResult::expense)
        .toList();
    if (!claimed.isEmpty()) {
      mailer.settlementRecorded(other, actor, saved, claimed);
    }
    return new Recorded(saved, results);
  }

  /**
   * Corrects the payments of a settle-up: how much was sent, when, how and with what reference.
   * The expenses it paid back stay as they are, and any new difference between the two is
   * carried into the balance. Only the parent who recorded it may change it, and the other
   * parent is told.
   */
  public ExpenseSettlement update(final ObjectId familyId, final ObjectId settlementId,
      final List<PaymentValues> payments) {
    final Parent actor = access.requireMember(familyId);
    final ExpenseSettlement settlement = find(familyId, settlementId);
    if (!actor.id().equals(settlement.recordedBy())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Only the parent who recorded this settle-up can change it");
    }
    if (settlement.amountPence() == 0) {
      throw badRequest("Nothing changed hands in this settle-up, so there is no payment to change");
    }
    final List<ExpenseSettlement.Payment> checked = payments(familyId, payments, true);
    final Parent other = expenseService.otherParent(familyId);
    final ExpenseSettlement saved = settlements.save(
        settlement.withPayments(checked, actor.id(), clock.instant()));
    audits.record(familyId, "expense_settlement", settlementId, "update", Map.of(
        "amountPence", saved.amountPence(),
        "paidPence", saved.paidPence(),
        "previousPaidPence", settlement.paidPence(),
        "currency", saved.currency(),
        "payments", saved.payments().size()));
    if (saved.paidPence() != settlement.paidPence()
        || !paidOnDates(saved).equals(paidOnDates(settlement))) {
      mailer.settlementChanged(other, actor, settlement, saved);
    }
    return saved;
  }

  /**
   * The parent who was paid confirms a settle-up's payments arrived, settling every expense in
   * it that still waits on them. The expenses' versions are read here rather than sent: one
   * being paid back can no longer change, so the claim is the only thing confirmed.
   */
  public List<ExpenseService.SettleResult> confirm(final ObjectId familyId,
      final ObjectId settlementId) {
    final Parent actor = access.requireMember(familyId);
    final ExpenseSettlement settlement = find(familyId, settlementId);
    final List<ExpenseService.SettleResult> results = new ArrayList<>();
    for (final ObjectId expenseId : settlement.expenseIds()) {
      final Expense expense = expenses.findByIdAndFamilyIdAndDeletedAtIsNull(expenseId, familyId)
          .orElse(null);
      if (expense == null || !Expense.CLAIMED.equals(expense.reimbursement().status())
          || !settlementId.equals(expense.reimbursement().settlementId())
          || !actor.id().equals(expense.payerId())) {
        continue;
      }
      try {
        results.add(new ExpenseService.SettleResult(expenseId, "confirmed",
            expenseService.confirm(familyId, expenseId, expense.version()), null));
      } catch (ResponseStatusException exception) {
        results.add(new ExpenseService.SettleResult(expenseId, "failed", null,
            exception.getReason()));
      }
    }
    if (results.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Nothing in this settle-up is waiting for you to confirm");
    }
    return results;
  }

  private ExpenseSettlement find(final ObjectId familyId, final ObjectId settlementId) {
    return settlements.findByIdAndFamilyId(settlementId, familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Settle-up not found"));
  }

  private static List<LocalDate> paidOnDates(final ExpenseSettlement settlement) {
    return settlement.payments().stream().map(ExpenseSettlement.Payment::paidOn).toList();
  }

  /**
   * Checks each payment as entered. Their total is not checked against the expenses: whatever
   * was really sent is recorded, and the difference is carried into the balance.
   */
  private List<ExpenseSettlement.Payment> payments(
      final ObjectId familyId,
      final List<PaymentValues> payments,
      final boolean moneyMoves) {
    final List<PaymentValues> given = payments == null ? List.of() : payments;
    if (!moneyMoves) {
      if (!given.isEmpty()) {
        throw badRequest("These cancel each other out, so there is no payment to record");
      }
      return List.of();
    }
    if (given.isEmpty() || given.size() > MAX_PAYMENTS) {
      throw badRequest("Record between 1 and " + MAX_PAYMENTS + " payments");
    }
    final LocalDate today = expenseService.today(familyId);
    final List<ExpenseSettlement.Payment> checked = new ArrayList<>();
    for (final PaymentValues payment : given) {
      if (payment.method() == null || !METHODS.contains(payment.method())) {
        throw badRequest("Say how each payment was made");
      }
      if (payment.amountPence() < 1 || payment.amountPence() > ExpenseService.MAX_AMOUNT_PENCE) {
        throw badRequest("Each payment needs an amount between £0.01 and %s"
            .formatted(ExpenseMoney.format(ExpenseService.MAX_AMOUNT_PENCE)));
      }
      if (payment.paidOn() == null) {
        throw badRequest("Say when each payment was made");
      }
      if (payment.paidOn().isAfter(today)) {
        throw badRequest("A payment can't be dated in the future");
      }
      if (payment.paidOn().isBefore(today.minusYears(DATE_WINDOW_YEARS))) {
        throw badRequest("Choose a payment date within " + DATE_WINDOW_YEARS + " years of today");
      }
      final String reference = payment.reference() == null || payment.reference().isBlank()
          ? null : payment.reference().trim();
      if (reference != null && reference.length() > MAX_REFERENCE) {
        throw badRequest("A reference can be up to " + MAX_REFERENCE + " characters");
      }
      checked.add(new ExpenseSettlement.Payment(payment.method(), payment.amountPence(),
          payment.paidOn(), reference));
    }
    return checked;
  }

  /** The note each settled expense carries: how it was paid, such as "Bank transfer". */
  static String methodNote(final List<ExpenseSettlement.Payment> payments) {
    if (payments.isEmpty()) {
      return "Settled against each other";
    }
    final Set<String> labels = new LinkedHashSet<>();
    payments.forEach(payment -> labels.add(switch (payment.method()) {
      case ExpenseSettlement.BANK_TRANSFER -> "bank transfer";
      case ExpenseSettlement.CASH -> "cash";
      default -> "other payment";
    }));
    final String joined = String.join(" and ", labels);
    final String note = Character.toUpperCase(joined.charAt(0)) + joined.substring(1);
    return payments.size() == 1 ? note : note + " (" + payments.size() + " payments)";
  }

  private static ResponseStatusException badRequest(final String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
