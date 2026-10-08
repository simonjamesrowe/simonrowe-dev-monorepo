import { Check, Paperclip, Pencil, Plus, Scale, Sparkles, UserPlus } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { useQuickAdd } from '../components/assistant';
import { EXPENSE_CATEGORIES } from '../components/expenses/categories';
import { CategoryTile } from '../components/expenses/CategoryTile';
import { ExpenseDetailDrawer } from '../components/expenses/ExpenseDetailDrawer';
import { ExpenseFormDrawer } from '../components/expenses/ExpenseFormDrawer';
import { InvitedCoparentBanner } from '../components/expenses/InvitedCoparentBanner';
import {
  EXPENSE_VIEWS,
  expenseStatus,
  inView,
  type ExpenseStatus,
  type ExpenseView,
} from '../components/expenses/expenseStatus';
import { formatMoney } from '../components/expenses/money';
import { ParentDot } from '../components/expenses/ParentDot';
import { firstName, parentTone } from '../components/expenses/parentTone';
import { settleable } from '../components/expenses/settle';
import { SettleUpDrawer } from '../components/expenses/SettleUpDrawer';
import { StatusChip } from '../components/expenses/StatusChip';
import { useToast } from '../components/ui/ToastProvider';
import {
  useChildren,
  useCurrentParentId,
  useExpenses,
  useExpenseSummary,
  useExpenseTransition,
  useFamilies,
  useParentsWithInvited,
} from '../hooks/api';
import type { Child, Parent } from '../lib/api/client';
import { apiErrorMessage } from '../lib/api/errorMessage';
import type { Expense, ExpenseCategory } from '../types/expenses';

type DetailMode = null | 'dispute' | 'claim' | 'markPaid';

const shortDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
const monthLabel = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });

function payerText(expense: Expense, me: Parent, otherName: string): string {
  if (!expense.payerId) return 'Payer not decided';
  const you = expense.payerId === me.id;
  if (expense.timing === 'paid') return you ? 'Paid by you' : `Paid by ${otherName}`;
  return you ? 'You will pay' : `${otherName} will pay`;
}

function shareLine(expense: Expense, me: Parent, otherName: string): string {
  const mine = expense.shares.find((share) => share.parentId === me.id)?.sharePence ?? 0;
  const theirs = expense.shares.find((share) => share.parentId !== me.id)?.sharePence ?? 0;
  return expense.timing === 'paid' && expense.payerId === me.id
    ? `${otherName}'s share ${formatMoney(theirs)}`
    : `Your share ${formatMoney(mine)}`;
}

const ExpensesPage = () => {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const quickAdd = useQuickAdd();
  const [params, setParams] = useSearchParams();
  const { data: families = [], isLoading: familiesLoading } = useFamilies();
  const familyId = families[0]?.id;
  const meId = useCurrentParentId(familyId);
  // With the invited co-parent: a cost can be logged against them before they join.
  const { data: parents = [] } = useParentsWithInvited(familyId);
  const { data: children = [] } = useChildren(familyId);
  const { data: expenses = [], isLoading } = useExpenses(familyId);
  const { data: summary } = useExpenseSummary(familyId);
  const transition = useExpenseTransition();
  const [childFilter, setChildFilter] = useState('all');
  const [categoryFilter, setCategoryFilter] = useState<'all' | ExpenseCategory>('all');
  const [editing, setEditing] = useState<Expense | null>(null);
  const [formOpen, setFormOpen] = useState(params.get('new') === '1');
  const [settleOpen, setSettleOpen] = useState(params.get('settle') === '1');
  const [detailMode, setDetailMode] = useState<DetailMode>(null);

  const me = parents.find((parent) => parent.id === meId);
  const others = parents.filter((parent) => parent.id !== meId);
  const other = others.find((parent) => parent.status === 'active')
    ?? others.find((parent) => parent.status === 'invited');
  const otherInvited = other?.status === 'invited';
  const otherName = firstName(other);
  const detailId = params.get('expense');
  const detail = expenses.find((expense) => expense.id === detailId) ?? null;

  const statuses = useMemo(() => {
    const map = new Map<string, ExpenseStatus>();
    if (me) expenses.forEach((expense) => map.set(expense.id, expenseStatus(expense, me.id, otherName)));
    return map;
  }, [expenses, me, otherName]);

  const needsCount = expenses.filter((expense) => statuses.get(expense.id)?.needsAction).length;
  const requestedView = params.get('view') as ExpenseView | null;
  const view: ExpenseView =
    requestedView && EXPENSE_VIEWS.some((entry) => entry.key === requestedView)
      ? requestedView
      : needsCount > 0
        ? 'needs'
        : 'all';

  const setParam = (key: string, value: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (value === null) next.delete(key);
        else next.set(key, value);
        return next;
      },
      { replace: true },
    );

  const openDetail = (expense: Expense, mode: DetailMode = null) => {
    setDetailMode(mode);
    setParam('expense', expense.id);
  };

  const quickStep = async (expense: Expense, name: 'agree' | 'reimbursement/confirm') => {
    if (!familyId) return;
    try {
      await transition.mutateAsync({ familyId, expense, transition: name });
      showToast({
        variant: 'success',
        title: name === 'agree' ? 'Agreed' : 'Settled',
        description:
          name === 'agree' && expense.timing === 'paid' && expense.owedPence > 0
            ? `${expense.title} now counts towards the balance.`
            : `${expense.title} is ${name === 'agree' ? 'agreed' : 'settled'}.`,
      });
    } catch (error) {
      showToast({ variant: 'error', title: 'That did not work', description: apiErrorMessage(error, 'Try again.') });
    }
  };

  if (familiesLoading || isLoading) {
    return <div className="expense-page expense-muted">Loading expenses…</div>;
  }
  if (!familyId || !me) {
    return (
      <div className="expense-page">
        <h1 className="expense-page__title">Expenses</h1>
        <p className="expense-muted">Set up your family to start tracking shared costs.</p>
      </div>
    );
  }
  if (!other) {
    return (
      <div className="expense-page">
        <h1 className="expense-page__title">Expenses</h1>
        <div className="expense-empty">
          <UserPlus size={28} aria-hidden="true" />
          <p>Invite your co-parent to start sharing costs.</p>
          <button type="button" className="expense-button expense-button--primary" onClick={() => navigate('/family-setup')}>
            Invite your co-parent
          </button>
        </div>
      </div>
    );
  }

  const childNames = (ids: string[]) =>
    ids
      .map((id) => children.find((child: Child) => child.id === id)?.fullName.split(/\s+/)[0])
      .filter(Boolean)
      .join(', ');
  const visible = expenses
    .filter((expense) => inView(view, expense, me.id, otherName))
    .filter((expense) => childFilter === 'all' || expense.childIds.includes(childFilter))
    .filter((expense) => categoryFilter === 'all' || expense.category === categoryFilter)
    .sort((left, right) =>
      view === 'upcoming' ? left.date.localeCompare(right.date) : right.date.localeCompare(left.date),
    );
  const balance = summary?.balance;
  const owedToMe = balance ? balance.creditorParentId === me.id : false;
  const square = !balance || balance.netPence === 0;
  const canSettle = settleable(expenses, me.id).length > 0;

  const rowActions = (expense: Expense, status: ExpenseStatus) =>
    status.actions.map((action) => {
      switch (action) {
        case 'agree':
          return (
            <button key={action} type="button" className="expense-button expense-button--primary expense-button--sm" onClick={() => quickStep(expense, 'agree')}>
              <Check size={15} aria-hidden="true" /> Agree
            </button>
          );
        case 'dispute':
          return (
            <button key={action} type="button" className="expense-button expense-button--danger expense-button--sm" onClick={() => openDetail(expense, 'dispute')}>
              Dispute
            </button>
          );
        case 'claim':
          return (
            <button key={action} type="button" className="expense-button expense-button--teal expense-button--sm" onClick={() => openDetail(expense, 'claim')}>
              Mark paid back
            </button>
          );
        case 'confirm':
          return (
            <button key={action} type="button" className="expense-button expense-button--primary expense-button--sm" onClick={() => quickStep(expense, 'reimbursement/confirm')}>
              <Check size={15} aria-hidden="true" /> Confirm received
            </button>
          );
        case 'edit':
          return (
            <button key={action} type="button" className="expense-button expense-button--ghost expense-button--sm" onClick={() => { setEditing(expense); setFormOpen(true); }}>
              <Pencil size={14} aria-hidden="true" /> Edit
            </button>
          );
        case 'markPaid':
          return (
            <button key={action} type="button" className="expense-button expense-button--teal expense-button--sm" onClick={() => openDetail(expense, 'markPaid')}>
              Mark as paid
            </button>
          );
        default:
          return null;
      }
    });

  let lastMonth = '';
  return (
    <div className="expense-page">
      <header className="expense-page__head">
        <div>
          <h1 className="expense-page__title">Expenses</h1>
          <p className="expense-page__sub">
            Shared costs{children.length ? ` for ${children.map((child) => child.fullName.split(/\s+/)[0]).join(' and ')}` : ''}
          </p>
        </div>
        <div className="expense-page__actions">
          {quickAdd.available && (
            <button type="button" className="expense-button expense-button--ghost expense-page__quick" onClick={quickAdd.open}>
              <Sparkles size={16} aria-hidden="true" /> Paste a note or receipt
            </button>
          )}
          <button type="button" className="expense-button expense-button--primary" onClick={() => { setEditing(null); setFormOpen(true); }}>
            <Plus size={16} aria-hidden="true" /> Add expense
          </button>
        </div>
      </header>

      {otherInvited && (
        <InvitedCoparentBanner familyId={familyId} invited={other} canManage={me.role === 'primary'} />
      )}

      <section className="expense-cards" aria-label="Summary">
        <article className={`expense-card expense-balance${square ? ' expense-balance--square' : owedToMe ? '' : ' expense-balance--owe'}`}>
          <div>
            <p className="expense-eyebrow">Balance</p>
            <p className="expense-balance__who">{square ? 'All square' : owedToMe ? `${otherName} owes you` : `You owe ${otherName}`}</p>
            <p className="expense-balance__amount">{formatMoney(balance?.netPence ?? 0)}</p>
          </div>
          {canSettle && (
            <button type="button" className="expense-button expense-button--primary" onClick={() => setSettleOpen(true)}>
              <Scale size={16} aria-hidden="true" /> Settle up
            </button>
          )}
          <p className="expense-balance__meta">
            {balance && balance.expenseCount > 0 && <span>across {balance.expenseCount} expense{balance.expenseCount === 1 ? '' : 's'}</span>}
            {balance && balance.awaitingYourConfirmationPence > 0 && (
              <strong>{formatMoney(balance.awaitingYourConfirmationPence)} waiting for you to confirm</strong>
            )}
            {balance && balance.awaitingTheirConfirmationPence > 0 && (
              <span>{formatMoney(balance.awaitingTheirConfirmationPence)} paid back, awaiting {otherName}</span>
            )}
            {(!balance || balance.expenseCount === 0) && <span>Nothing outstanding</span>}
          </p>
        </article>
        <article className="expense-card expense-stat">
          <p className="expense-eyebrow">Needs your OK</p>
          <p className="expense-stat__value expense-stat__value--amber">
            {summary?.needsYourAgreement.count ?? 0} expense{summary?.needsYourAgreement.count === 1 ? '' : 's'}
          </p>
          <p className="expense-muted">
            {formatMoney(summary?.needsYourAgreement.totalPence ?? 0)} total
            {summary && summary.awaitingOther > 0 ? ` · ${summary.awaitingOther} waiting for ${otherName}` : ''}
          </p>
          {summary && summary.needsYourAgreement.count > 0 && (
            <button type="button" className="expense-button expense-button--teal expense-button--sm expense-stat__action" onClick={() => setParam('view', 'needs')}>
              Review
            </button>
          )}
        </article>
        <article className="expense-card expense-stat">
          <p className="expense-eyebrow">Coming up</p>
          <p className="expense-stat__value">{formatMoney(summary?.upcoming.totalPence ?? 0)}</p>
          <p className="expense-muted">{summary?.upcoming.count ?? 0} due in the next 30 days</p>
          <p className="expense-muted">Your share {formatMoney(summary?.upcoming.yourSharePence ?? 0)}</p>
        </article>
      </section>

      <div className="expense-toolbar">
        <nav className="expense-tabs" aria-label="Expense views">
          {EXPENSE_VIEWS.map((entry) => {
            const count = expenses.filter((expense) => inView(entry.key, expense, me.id, otherName)).length;
            return (
              <button
                key={entry.key}
                type="button"
                aria-pressed={view === entry.key}
                className={`expense-tab${view === entry.key ? ' is-on' : ''}`}
                onClick={() => setParam('view', entry.key)}
              >
                {entry.label}
                {entry.counted && count > 0 && <span className="expense-tab__count">{count}</span>}
              </button>
            );
          })}
        </nav>
        <div className="expense-filters">
          <select className="expense-select" aria-label="Child" value={childFilter} onChange={(event) => setChildFilter(event.target.value)}>
            <option value="all">All children</option>
            {children.map((child) => (
              <option key={child.id} value={child.id}>
                {child.fullName.split(/\s+/)[0]}
              </option>
            ))}
          </select>
          <select
            className="expense-select"
            aria-label="Type"
            value={categoryFilter}
            onChange={(event) => setCategoryFilter(event.target.value as 'all' | ExpenseCategory)}
          >
            <option value="all">All types</option>
            {Object.entries(EXPENSE_CATEGORIES).map(([key, info]) => (
              <option key={key} value={key}>
                {info.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="expense-list">
        {expenses.length === 0 ? (
          <div className="expense-empty">
            <p>No expenses yet.</p>
            <button type="button" className="expense-button expense-button--primary" onClick={() => { setEditing(null); setFormOpen(true); }}>
              <Plus size={16} aria-hidden="true" /> Add your first expense
            </button>
          </div>
        ) : visible.length === 0 ? (
          <p className="expense-empty">{view === 'needs' ? 'Nothing needs you right now.' : 'No expenses here.'}</p>
        ) : (
          visible.map((expense) => {
            const status = statuses.get(expense.id) ?? expenseStatus(expense, me.id, otherName);
            const payer = expense.payerId === me.id ? me : expense.payerId === other.id ? other : undefined;
            const month = monthLabel(expense.date);
            const header = view !== 'upcoming' && month !== lastMonth;
            lastMonth = month;
            return (
              <div key={expense.id}>
                {header && <p className="expense-month">{month.toUpperCase()}</p>}
                <article
                  className={`expense-row${status.needsAction ? ' expense-row--needs' : ''}`}
                  data-testid={`expense-row-${expense.id}`}
                >
                  <button type="button" className="expense-row__open" onClick={() => openDetail(expense)} aria-label={`Open ${expense.title}`}>
                    <CategoryTile category={expense.category} />
                    <span className="expense-row__main">
                      <span className="expense-row__title">{expense.title}</span>
                      <span className="expense-row__meta">
                        <ParentDot tone={payer ? parentTone(payer) : null} />
                        <span>{payerText(expense, me, otherName)}</span>
                        <span className="expense-row__sep">·</span>
                        <span>{childNames(expense.childIds)}</span>
                        <span className="expense-row__sep">·</span>
                        <span>{EXPENSE_CATEGORIES[expense.category].label}</span>
                        <span className="expense-row__sep">·</span>
                        <span>{expense.timing === 'paid' ? shortDate(expense.date) : `Due ${shortDate(expense.date)}`}</span>
                        {expense.receipts.length > 0 && (
                          <>
                            <span className="expense-row__sep">·</span>
                            <span>
                              <Paperclip size={13} aria-label="Receipts" /> {expense.receipts.length}
                            </span>
                          </>
                        )}
                      </span>
                    </span>
                    <span className="expense-row__money">
                      <span className="expense-row__amount">{formatMoney(expense.amountPence)}</span>
                      <span className="expense-row__share">{shareLine(expense, me, otherName)}</span>
                    </span>
                  </button>
                  <div className="expense-row__status">
                    <StatusChip status={status} />
                    <div className="expense-row__actions">{rowActions(expense, status)}</div>
                  </div>
                </article>
              </div>
            );
          })
        )}
      </div>

      <ExpenseFormDrawer
        open={formOpen}
        familyId={familyId}
        me={me}
        other={other}
        children={children}
        expense={editing}
        onClose={() => {
          setFormOpen(false);
          setParam('new', null);
        }}
        onOpenQuickAdd={quickAdd.available ? () => { setFormOpen(false); quickAdd.open(); } : undefined}
        onSaved={(saved, failures) => {
          setFormOpen(false);
          setParam('new', null);
          showToast({
            variant: failures ? 'error' : 'success',
            title: editing ? 'Saved' : 'Expense added',
            description: failures
              ? `Expense saved. ${failures} receipt${failures === 1 ? '' : 's'} didn't upload. Retry from the expense.`
              : saved.agreement.status === 'pending'
                ? otherInvited
                  ? `${otherName} can agree to it once they join.`
                  : `${otherName} has been asked to agree.`
                : 'Nothing about who owes what changed.',
          });
          setEditing(null);
        }}
      />
      <ExpenseDetailDrawer
        familyId={familyId}
        me={me}
        other={other}
        children={children}
        expense={detail}
        initialMode={detailMode}
        onClose={() => {
          setDetailMode(null);
          setParam('expense', null);
        }}
        onEdit={(expense) => {
          setParam('expense', null);
          setEditing(expense);
          setFormOpen(true);
        }}
        onDiscuss={(expense) =>
          navigate(`/messages?compose=message&subject=${encodeURIComponent(`About: ${expense.title} (${formatMoney(expense.amountPence)})`)}`)
        }
      />
      <SettleUpDrawer
        open={settleOpen}
        familyId={familyId}
        me={me}
        other={other}
        expenses={expenses}
        summary={summary}
        onClose={() => {
          setSettleOpen(false);
          setParam('settle', null);
        }}
      />
    </div>
  );
};

export default ExpensesPage;
