import { AlertTriangle, ArrowLeft, Landmark, Lock, Sparkles, Upload } from 'lucide-react';
import { useRef, useState, type DragEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import { CategoryTile } from '../components/expenses/CategoryTile';
import { ExpenseFormDrawer, type ExpenseFormSource } from '../components/expenses/ExpenseFormDrawer';
import { formatMoney } from '../components/expenses/money';
import { firstName } from '../components/expenses/parentTone';
import { useToast } from '../components/ui/ToastProvider';
import {
  useCheckStatementRows,
  useChildren,
  useConvertStatementTransaction,
  useCurrentParentId,
  useDismissStatementTransaction,
  useFamilies,
  useForgetStatementTransaction,
  useParentsWithInvited,
  useStatementOverview,
  useStatementTransactions,
  useUploadStatement,
} from '../hooks/api';
import { apiErrorMessage } from '../lib/api/errorMessage';
import type {
  StatementCheckResult,
  StatementConfidence,
  StatementRow,
  StatementRowInput,
  StatementTransaction,
  StatementUploadResult,
  StatementUploadSummary,
} from '../types/statements';

const BATCH = 40;
const PARALLEL = 3;
const ACCEPT = '.csv,.txt,text/csv,text/plain';
const BANKS = [
  'Starling · CSV',
  'Monzo · CSV',
  'American Express · CSV',
  'Santander current account · TXT',
  'Santander credit card · TXT',
];

type Tab = 'import' | 'review' | 'dismissed' | 'logged' | 'latest';

const CONFIDENCE: Record<StatementConfidence, { label: string; tone: string }> = {
  high: { label: 'Likely shared', tone: 'high' },
  medium: { label: 'Maybe shared', tone: 'medium' },
  low: { label: 'Unsure', tone: 'low' },
};

const shortDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
const longDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

function period(from: string | null, to: string | null): string {
  return from && to ? `${shortDate(from)} – ${longDate(to)}` : '';
}

function toInput(row: StatementRow): StatementRowInput {
  return {
    fingerprint: row.fingerprint,
    date: row.date,
    description: row.description,
    details: row.details,
    amountPence: row.amountPence,
    account: row.account,
  };
}

/** One upload read in this visit, with its rows as checking updates them. */
interface Session {
  result: StatementUploadResult;
  rows: StatementRow[];
  checked: number;
  failed: number;
  done: boolean;
}

/** What the convert drawer is turning into an expense. */
interface Converting {
  fingerprint: string;
  uploadId: string | null;
  transaction: StatementRowInput | null;
  heading: string;
  detail: string;
  draft: ExpenseFormSource['draft'];
}

const StatementsPage = () => {
  const { showToast } = useToast();
  const [params, setParams] = useSearchParams();
  const { data: families = [], isLoading: familiesLoading } = useFamilies();
  const familyId = families[0]?.id;
  const meId = useCurrentParentId(familyId);
  const { data: parents = [] } = useParentsWithInvited(familyId);
  const { data: children = [] } = useChildren(familyId);
  const { data: overview } = useStatementOverview(familyId);
  const requested = params.get('tab') as Tab | null;
  const [sessions, setSessions] = useState<Session[]>([]);
  const [uploading, setUploading] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [converting, setConverting] = useState<Converting | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const upload = useUploadStatement();
  const checkRows = useCheckStatementRows();
  const dismiss = useDismissStatementTransaction();
  const forget = useForgetStatementTransaction();
  const convert = useConvertStatementTransaction();

  const tab: Tab =
    requested && (requested !== 'latest' || sessions.length > 0)
      ? requested
      : overview && overview.toReview > 0
        ? 'review'
        : 'import';
  const status = tab === 'dismissed' ? 'dismissed' : tab === 'logged' ? 'logged' : 'suggested';
  const listed = useStatementTransactions(familyId, status, tab === 'review' || tab === 'dismissed' || tab === 'logged');

  const me = parents.find((parent) => parent.id === meId);
  const others = parents.filter((parent) => parent.id !== meId);
  const other =
    others.find((parent) => parent.status === 'active') ?? others.find((parent) => parent.status === 'invited');
  const otherName = firstName(other);

  const setTab = (next: Tab) =>
    setParams(
      (current) => {
        const updated = new URLSearchParams(current);
        updated.set('tab', next);
        return updated;
      },
      { replace: true },
    );

  const updateSession = (index: number, change: (session: Session) => Session) =>
    setSessions((current) => current.map((session, position) => (position === index ? change(session) : session)));

  const applyResults = (index: number, results: StatementCheckResult[]) => {
    const states = new Map(results.map((result) => [result.fingerprint, result]));
    updateSession(index, (session) => ({
      ...session,
      checked: session.checked + results.length,
      rows: session.rows.map((row) => {
        const result = states.get(row.fingerprint);
        return result ? { ...row, state: result.state, transactionId: result.transaction?.id ?? row.transactionId } : row;
      }),
    }));
  };

  /** Sends the rows never seen before to be checked, a few batches at a time. */
  const checkSession = async (
    index: number,
    result: StatementUploadResult,
  ): Promise<{ suggested: number; failed: number }> => {
    if (!familyId || !result.upload || !result.aiEnabled) return { suggested: 0, failed: 0 };
    const uploadId = result.upload.id;
    const fresh = result.rows.filter((row) => row.state === null).map(toInput);
    const batches: StatementRowInput[][] = [];
    for (let start = 0; start < fresh.length; start += BATCH) batches.push(fresh.slice(start, start + BATCH));
    let suggested = 0;
    let failed = 0;
    let next = 0;
    const worker = async () => {
      while (next < batches.length) {
        const batch = batches[next++];
        try {
          const results = await checkRows.mutateAsync({ familyId, uploadId, rows: batch });
          suggested += results.filter((entry) => entry.state === 'suggested').length;
          applyResults(index, results);
        } catch {
          failed += batch.length;
          updateSession(index, (session) => ({ ...session, failed: session.failed + batch.length }));
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(PARALLEL, batches.length) }, worker));
    updateSession(index, (session) => ({ ...session, done: true }));
    return { suggested, failed };
  };

  const uploadFiles = async (files: File[]) => {
    if (!familyId || files.length === 0) return;
    setUploading(true);
    let suggested = 0;
    let failed = 0;
    // Each file read adds a session; the state captured here does not see them until next render.
    let index = sessions.length;
    for (const file of files) {
      let result: StatementUploadResult;
      try {
        result = await upload.mutateAsync({ familyId, file });
      } catch (error) {
        showToast({
          variant: 'error',
          title: `${file.name} couldn't be read`,
          description: apiErrorMessage(error, 'Try another file.'),
        });
        continue;
      }
      const position = index++;
      setSessions((current) => [
        ...current,
        { result, rows: result.rows, checked: 0, failed: 0, done: !result.upload || !result.aiEnabled },
      ]);
      if (!result.upload) {
        showToast({
          variant: 'info',
          title: 'Nothing new in that file',
          description: `All ${result.rows.length} spending transactions in your ${result.account} statement were already checked, so nothing was added.`,
        });
        continue;
      }
      setTab('import');
      const checked = await checkSession(position, result);
      suggested += checked.suggested;
      failed += checked.failed;
    }
    setUploading(false);
    if (failed > 0) {
      showToast({
        variant: 'error',
        title: `${failed} transaction${failed === 1 ? '' : 's'} couldn't be checked`,
        description: 'Upload the statement again to finish. Nothing already checked is repeated.',
      });
    }
    if (suggested > 0) {
      showToast({
        variant: 'success',
        title: `${suggested} possible shared cost${suggested === 1 ? '' : 's'} found`,
        description: 'Review them, and turn the right ones into expenses.',
      });
      setTab('review');
    }
  };

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragging(false);
    void uploadFiles(Array.from(event.dataTransfer.files));
  };

  const openSuggestion = (transaction: StatementTransaction) => {
    if (!transaction.date || !transaction.description || transaction.amountPence === null) return;
    const suggestion = transaction.suggestion;
    setConverting({
      fingerprint: transaction.fingerprint,
      uploadId: transaction.uploadId,
      transaction: null,
      heading: `${transaction.description} · ${formatMoney(transaction.amountPence)}`,
      detail: `${transaction.account ?? 'Your statement'} · ${longDate(transaction.date)}`,
      draft: {
        title: suggestion?.title ?? transaction.description,
        amountPence: transaction.amountPence,
        date: transaction.date,
        category: suggestion?.category ?? 'other',
        childIds: suggestion?.childIds ?? [],
      },
    });
  };

  const openRow = (row: StatementRow, uploadId: string | null) =>
    setConverting({
      fingerprint: row.fingerprint,
      uploadId,
      transaction: toInput(row),
      heading: `${row.description} · ${formatMoney(row.amountPence)}`,
      detail: `${row.account} · ${longDate(row.date)}`,
      draft: { title: row.description, amountPence: row.amountPence, date: row.date, category: 'other', childIds: [] },
    });

  const notShared = async (transaction: StatementTransaction) => {
    if (!familyId) return;
    try {
      await dismiss.mutateAsync({ familyId, id: transaction.id });
      setSessions((current) =>
        current.map((session) => ({
          ...session,
          rows: session.rows.map((row) =>
            row.fingerprint === transaction.fingerprint ? { ...row, state: 'dismissed' as const } : row,
          ),
        })),
      );
    } catch (error) {
      showToast({ variant: 'error', title: 'That did not work', description: apiErrorMessage(error, 'Try again.') });
    }
  };

  const forgetDecision = async (transaction: StatementTransaction) => {
    if (!familyId) return;
    try {
      await forget.mutateAsync({ familyId, id: transaction.id });
      showToast({
        variant: 'success',
        title: 'Decision forgotten',
        description: 'It will be checked again next time you upload that statement.',
      });
    } catch (error) {
      showToast({ variant: 'error', title: 'That did not work', description: apiErrorMessage(error, 'Try again.') });
    }
  };

  if (familiesLoading) {
    return <div className="expense-page expense-muted">Loading…</div>;
  }
  if (!familyId || !me || !other) {
    return (
      <div className="expense-page">
        <p className="statement-crumb">
          <Link to="/expenses">Expenses</Link> › Statements
        </p>
        <h1 className="expense-page__title">Import statements</h1>
        <p className="expense-muted">Invite your co-parent before importing statements to share costs from.</p>
      </div>
    );
  }

  const source: ExpenseFormSource | null = converting
    ? {
        key: converting.fingerprint,
        heading: converting.heading,
        detail: converting.detail,
        draft: converting.draft,
        submit: (request) =>
          convert.mutateAsync({
            familyId,
            fingerprint: converting.fingerprint,
            uploadId: converting.uploadId,
            transaction: converting.transaction,
            expense: request,
          }),
      }
    : null;

  const uploadRow = (summary: StatementUploadSummary) => {
    const live = sessions.find((session) => session.result.upload?.id === summary.id);
    const checked = live ? live.checked : summary.checkedCount;
    const checking = live ? !live.done : summary.status === 'checking';
    const suggested = live ? live.rows.filter((row) => row.state === 'suggested').length : summary.suggestedCount;
    return (
      <article key={summary.id} className="statement-upload" data-testid={`statement-upload-${summary.id}`}>
        <span className="statement-upload__icon" aria-hidden="true">
          <Landmark size={20} />
        </span>
        <div className="statement-upload__main">
          <p className="statement-upload__title">
            <strong>{summary.account}</strong>
            <span> · {period(summary.from, summary.to)}</span>
          </p>
          {checking ? (
            <>
              <p className="expense-muted">
                Checking {summary.newCount} transactions for shared costs… {checked} of {summary.newCount}
              </p>
              <div
                className="statement-progress"
                role="progressbar"
                aria-valuemin={0}
                aria-valuemax={summary.newCount}
                aria-valuenow={checked}
                aria-label={`Checking ${summary.account}`}
              >
                <i style={{ width: `${Math.round((checked / Math.max(1, summary.newCount)) * 100)}%` }} />
              </div>
            </>
          ) : (
            <p className="expense-muted">
              {summary.spendingCount} spending transaction{summary.spendingCount === 1 ? '' : 's'}
              {summary.moneyInCount > 0 ? ` · ${summary.moneyInCount} money in ignored` : ''}
              {summary.status === 'off'
                ? ' · suggestions are switched off'
                : summary.status === 'incomplete' || (live && live.failed > 0)
                  ? ' · not all checked: upload it again to finish'
                  : suggested > 0
                    ? ` · ${suggested} possible shared cost${suggested === 1 ? '' : 's'}`
                    : ' · nothing looked shared'}
            </p>
          )}
        </div>
        {checking ? (
          <span className="statement-chip statement-chip--low">Checking</span>
        ) : suggested > 0 ? (
          <button type="button" className="expense-button expense-button--teal expense-button--sm" onClick={() => setTab('review')}>
            Review
          </button>
        ) : (
          <span className="statement-chip statement-chip--seen">Done</span>
        )}
      </article>
    );
  };

  const matchWarning = (match: StatementTransaction['match']) =>
    match && (
      <p className="statement-warn">
        <AlertTriangle size={14} aria-hidden="true" /> “{match.title}” is already an expense on {shortDate(match.date)}
      </p>
    );

  const suggestionRow = (transaction: StatementTransaction) => {
    const suggestion = transaction.suggestion;
    const confidence = CONFIDENCE[suggestion?.confidence ?? 'low'];
    const amount = transaction.amountPence ?? 0;
    return (
      <article key={transaction.id} className="statement-row" data-testid={`statement-suggestion-${transaction.id}`}>
        <CategoryTile category={suggestion?.category ?? 'other'} />
        <div className="statement-row__main">
          <p className="statement-row__title">{transaction.description}</p>
          <p className="statement-row__meta">
            <span className="statement-bank">{transaction.account}</span>
            {transaction.date && <span>{shortDate(transaction.date)}</span>}
            {transaction.details && <span>· {transaction.details}</span>}
          </p>
          {suggestion && (
            <p className="statement-ai">
              <Sparkles size={13} aria-hidden="true" /> {suggestion.title}
              {suggestion.reason ? ` — ${suggestion.reason}` : ''}
            </p>
          )}
          {matchWarning(transaction.match)}
        </div>
        <p className="statement-row__money">
          {formatMoney(amount)}
          <small>
            {otherName}'s share {formatMoney(Math.round(amount / 2))}
          </small>
        </p>
        <div className="statement-row__actions">
          <span className={`statement-chip statement-chip--${confidence.tone}`}>{confidence.label}</span>
          <div className="statement-row__buttons">
            <button
              type="button"
              className={`expense-button expense-button--sm ${suggestion?.confidence === 'low' ? 'expense-button--ghost' : 'expense-button--primary'}`}
              onClick={() => openSuggestion(transaction)}
            >
              Turn into expense
            </button>
            <button type="button" className="expense-button expense-button--text" onClick={() => notShared(transaction)}>
              Not shared
            </button>
          </div>
        </div>
      </article>
    );
  };

  const decidedRow = (transaction: StatementTransaction) => (
    <article key={transaction.id} className="statement-row statement-row--compact">
      <div className="statement-row__main">
        <p className="statement-row__title">{transaction.merchant}</p>
        <p className="statement-row__meta">
          {transaction.status === 'logged'
            ? transaction.expenseTitle
              ? `Logged as “${transaction.expenseTitle}”`
              : 'The expense it became has been removed'
            : 'Marked not shared'}
          {transaction.decidedAt && ` · ${longDate(transaction.decidedAt.slice(0, 10))}`}
        </p>
      </div>
      {transaction.status === 'logged' && transaction.expenseTitle && transaction.expenseId ? (
        <Link className="expense-button expense-button--ghost expense-button--sm" to={`/expenses?expense=${transaction.expenseId}`}>
          Open expense
        </Link>
      ) : transaction.status === 'dismissed' ? (
        <button type="button" className="expense-button expense-button--text" onClick={() => forgetDecision(transaction)}>
          Forget
        </button>
      ) : null}
    </article>
  );

  const latestRow = (row: StatementRow, uploadId: string | null) => (
    <article key={row.fingerprint} className="statement-row" data-testid={`statement-row-${row.fingerprint}`}>
      <CategoryTile category="other" />
      <div className="statement-row__main">
        <p className="statement-row__title">{row.description}</p>
        <p className="statement-row__meta">
          <span className="statement-bank">{row.account}</span>
          <span>{shortDate(row.date)}</span>
          {row.details && <span>· {row.details}</span>}
        </p>
        {row.state !== 'logged' && matchWarning(row.match)}
      </div>
      <p className="statement-row__money">{formatMoney(row.amountPence)}</p>
      <div className="statement-row__actions">
        {row.state === 'logged' ? (
          <span className="statement-chip statement-chip--done">Logged</span>
        ) : (
          <>
            {row.state === 'suggested' && <span className="statement-chip statement-chip--medium">Suggested</span>}
            {row.state === 'dismissed' && <span className="statement-chip statement-chip--seen">Not shared</span>}
            <button type="button" className="expense-button expense-button--ghost expense-button--sm" onClick={() => openRow(row, uploadId)}>
              Turn into expense
            </button>
          </>
        )}
      </div>
    </article>
  );

  const transactions = listed.data ?? [];
  const latestCount = sessions.reduce((total, session) => total + session.rows.length, 0);
  const tabs: { key: Tab; label: string; count?: number }[] = [
    { key: 'import', label: 'Import' },
    { key: 'review', label: 'To review', count: overview?.toReview ?? 0 },
    { key: 'dismissed', label: 'Not shared', count: overview?.dismissed ?? 0 },
    { key: 'logged', label: 'Logged', count: overview?.logged ?? 0 },
    ...(sessions.length > 0 ? [{ key: 'latest' as const, label: 'Latest upload', count: latestCount }] : []),
  ];

  return (
    <div className="expense-page">
      <p className="statement-crumb">
        <Link to="/expenses">
          <ArrowLeft size={14} aria-hidden="true" /> Expenses
        </Link>{' '}
        › Statements
      </p>
      <header className="expense-page__head">
        <div>
          <h1 className="expense-page__title">{tab === 'import' ? 'Import statements' : 'Possible shared costs'}</h1>
          <p className="expense-page__sub">
            {tab === 'import'
              ? "Find the shared costs you paid for and haven't logged yet"
              : `Turn one into an expense and ${otherName} is asked to agree to it`}
          </p>
        </div>
        {tab !== 'import' && (
          <button type="button" className="expense-button expense-button--ghost" onClick={() => setTab('import')}>
            <Upload size={16} aria-hidden="true" /> Import more
          </button>
        )}
      </header>

      <nav className="expense-tabs statement-tabs" aria-label="Statement views">
        {tabs.map((entry) => (
          <button
            key={entry.key}
            type="button"
            aria-pressed={tab === entry.key}
            className={`expense-tab${tab === entry.key ? ' is-on' : ''}`}
            onClick={() => setTab(entry.key)}
          >
            {entry.label}
            {entry.count !== undefined && entry.count > 0 && <span className="expense-tab__count">{entry.count}</span>}
          </button>
        ))}
      </nav>

      {tab === 'import' && (
        <>
          <div
            className={`statement-drop${dragging ? ' is-dragging' : ''}`}
            onDragOver={(event) => {
              event.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={onDrop}
            data-testid="statement-drop"
          >
            <Upload size={30} aria-hidden="true" />
            <h2>Drop statements here, or choose files</h2>
            <p className="expense-muted">Several at once is fine. Uploading one you've already imported adds nothing.</p>
            <div className="statement-banks">
              {BANKS.map((bank) => (
                <span key={bank} className="statement-bankpill">
                  {bank}
                </span>
              ))}
            </div>
            <button
              type="button"
              className="expense-button expense-button--primary"
              disabled={uploading}
              onClick={() => fileInput.current?.click()}
            >
              {uploading ? 'Working…' : 'Choose files'}
            </button>
            <input
              ref={fileInput}
              type="file"
              multiple
              accept={ACCEPT}
              className="sr-only"
              aria-label="Statement files"
              onChange={(event) => {
                void uploadFiles(Array.from(event.target.files ?? []));
                event.target.value = '';
              }}
            />
          </div>
          <div className="statement-privacy">
            <Lock size={18} aria-hidden="true" />
            <p>
              <strong>Your statement isn't kept, and {otherName} never sees it.</strong> CoParent reads your spending, keeps
              a one-way fingerprint of each transaction so it's never suggested twice, and holds the details of possible
              shared costs until you decide. Money coming in is ignored. {otherName} sees an expense only when you create
              one.
            </p>
          </div>
          {overview && !overview.aiEnabled && (
            <p className="expense-callout expense-callout--amber">
              <AlertTriangle size={16} aria-hidden="true" />
              Suggestions are switched off, so nothing is picked out for you. You can still turn any transaction from an
              upload into an expense.
            </p>
          )}
          {(overview?.uploads.length ?? 0) > 0 && <p className="expense-month">YOUR UPLOADS</p>}
          <div className="expense-list">{overview?.uploads.map(uploadRow)}</div>
        </>
      )}

      {tab === 'review' && (
        <div className="expense-list">
          {listed.isLoading ? (
            <p className="expense-muted">Loading…</p>
          ) : transactions.length === 0 ? (
            <div className="expense-empty">
              <p>Nothing waiting for you.</p>
              <button type="button" className="expense-button expense-button--primary" onClick={() => setTab('import')}>
                <Upload size={16} aria-hidden="true" /> Import a statement
              </button>
            </div>
          ) : (
            transactions.map(suggestionRow)
          )}
        </div>
      )}

      {(tab === 'dismissed' || tab === 'logged') && (
        <div className="expense-list">
          {listed.isLoading ? (
            <p className="expense-muted">Loading…</p>
          ) : transactions.length === 0 ? (
            <p className="expense-empty">{tab === 'logged' ? 'Nothing logged from a statement yet.' : 'Nothing here.'}</p>
          ) : (
            transactions.map(decidedRow)
          )}
        </div>
      )}

      {tab === 'latest' && (
        <div className="expense-list">
          {sessions.map((session) => (
            <section key={`${session.result.account}-${session.result.from}-${session.result.rows.length}`}>
              <p className="expense-month">
                {session.result.account.toUpperCase()} · {period(session.result.from, session.result.to).toUpperCase()}
              </p>
              {session.rows.map((row) => latestRow(row, session.result.upload?.id ?? null))}
            </section>
          ))}
          <p className="statement-note">
            Shown while you're on this page. Transactions that weren't suggested aren't kept: upload the statement again to
            pick one later. It never adds duplicates.
          </p>
        </div>
      )}

      <ExpenseFormDrawer
        open={converting !== null}
        familyId={familyId}
        me={me}
        other={other}
        children={children}
        expense={null}
        source={source}
        onClose={() => setConverting(null)}
        onSaved={(saved, failures) => {
          const fingerprint = converting?.fingerprint;
          setConverting(null);
          setSessions((current) =>
            current.map((session) => ({
              ...session,
              rows: session.rows.map((row) =>
                row.fingerprint === fingerprint ? { ...row, state: 'logged' as const, expenseId: saved.id } : row,
              ),
            })),
          );
          showToast({
            variant: failures ? 'error' : 'success',
            title: 'Expense added',
            description: failures
              ? `Expense saved. ${failures} receipt${failures === 1 ? '' : 's'} didn't upload. Retry from the expense.`
              : other.status === 'invited'
                ? `${otherName} can agree to it once they join.`
                : `${otherName} has been asked to agree.`,
          });
        }}
      />
    </div>
  );
};

export default StatementsPage;
