// Pounds sterling, in pence, and the who-owes-what arithmetic the form previews. The backend's
// ExpenseMath is the authority: every saved expense carries its own owedPence, so this is used
// only before a value has been saved.

const GBP = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' });

/** Formats pence as pounds sterling with pence shown, e.g. 4500 → "£45.00". Never "$". */
export function formatMoney(pence: number): string {
  return GBP.format(pence / 100);
}

/** Pounds as typed into the amount field. Anchored and bounded, so it cannot backtrack. */
const AMOUNT = /^\d{1,6}(\.\d{1,2})?$/;

/** Parses "45", "45.5" or "45.50" into pence, or null for anything else (including zero). */
export function parsePounds(value: string): number | null {
  const trimmed = value.trim().replace(/^£/, '');
  if (!AMOUNT.test(trimmed)) return null;
  const [whole, fraction = ''] = trimmed.split('.');
  const pence = Number(whole) * 100 + Number(`${fraction}00`.slice(0, 2));
  return pence > 0 ? pence : null;
}

/** Pence back to the amount field's text, e.g. 4500 → "45.00". */
export function penceToPounds(pence: number): string {
  return (pence / 100).toFixed(2);
}

/** {@code amount × percent / 100}, rounded half up: the same rule as ExpenseMath.percentOf. */
export function percentOf(amountPence: number, percent: number): number {
  return Math.floor((amountPence * percent + 50) / 100);
}

export type PayerChoice = 'me' | 'them' | 'undecided';

export interface SharePreview {
  /** The signed-in parent's share of the cost. */
  mine: number;
  /** The other parent's share. */
  theirs: number;
  /** Positive: the other parent will owe me. Negative: I will owe them. Zero: nothing owed. */
  owedToMe: number;
}

/**
 * Splits an amount the way the server will, from the signed-in parent's side. The parent who
 * did not pay owes their percentage; with no payer yet, nobody owes anything.
 */
export function previewShares(amountPence: number, myPercent: number, payer: PayerChoice): SharePreview {
  if (payer === 'me') {
    const owed = percentOf(amountPence, 100 - myPercent);
    return { mine: amountPence - owed, theirs: owed, owedToMe: owed };
  }
  if (payer === 'them') {
    const owed = percentOf(amountPence, myPercent);
    return { mine: owed, theirs: amountPence - owed, owedToMe: -owed };
  }
  const mine = percentOf(amountPence, myPercent);
  return { mine, theirs: amountPence - mine, owedToMe: 0 };
}

export interface SummaryText {
  tone: 'owed' | 'owe' | 'neutral';
  lead: string;
  outcome: string | null;
}

/**
 * The sentence under the form that says what the choices mean. It is the point of the form:
 * "who paid" and "how it's shared" are separate, and this is where they meet.
 */
export function describeExpense(options: {
  amountPence: number | null;
  myPercent: number;
  payer: PayerChoice;
  timing: 'paid' | 'upcoming';
  otherName: string;
  dueLabel?: string;
}): SummaryText {
  const { amountPence, myPercent, payer, timing, otherName, dueLabel } = options;
  if (!amountPence) {
    return { tone: 'neutral', lead: 'Enter an amount to see who owes what.', outcome: null };
  }
  const total = formatMoney(amountPence);
  const preview = previewShares(amountPence, myPercent, payer);
  if (timing === 'paid') {
    if (payer === 'me') {
      return preview.owedToMe > 0
        ? {
            tone: 'owed',
            lead: `You paid ${total}. Your share is ${formatMoney(preview.mine)}.`,
            outcome: `${otherName} will owe you ${formatMoney(preview.owedToMe)} once ${otherName} agrees.`,
          }
        : {
            tone: 'neutral',
            lead: `You paid ${total} and you're covering all of it.`,
            outcome: `Nothing is owed. ${otherName} will still be asked to agree.`,
          };
    }
    return -preview.owedToMe > 0
      ? {
          tone: 'owe',
          lead: `${otherName} paid ${total}. Your share is ${formatMoney(preview.mine)}.`,
          outcome: `You'll owe ${otherName} ${formatMoney(-preview.owedToMe)} once ${otherName} agrees.`,
        }
      : {
          tone: 'neutral',
          lead: `${otherName} paid ${total} and is covering all of it.`,
          outcome: 'Nothing is owed.',
        };
  }
  const due = dueLabel ? ` on ${dueLabel}` : '';
  if (payer === 'undecided') {
    return {
      tone: 'neutral',
      lead: `${total} due${due}. Nobody is down to pay yet.`,
      outcome: `Your share will be ${formatMoney(preview.mine)}, ${otherName}'s ${formatMoney(preview.theirs)}.`,
    };
  }
  const who = payer === 'me' ? 'You' : otherName;
  const outcome =
    preview.owedToMe > 0
      ? `${otherName} will owe you ${formatMoney(preview.owedToMe)} once it's paid.`
      : preview.owedToMe < 0
        ? `You'll owe ${otherName} ${formatMoney(-preview.owedToMe)} once it's paid.`
        : 'Nothing will be owed.';
  return {
    tone: preview.owedToMe < 0 ? 'owe' : preview.owedToMe > 0 ? 'owed' : 'neutral',
    lead: `${who} will pay ${total} when it's due${due}. Your share will be ${formatMoney(preview.mine)}.`,
    outcome,
  };
}
