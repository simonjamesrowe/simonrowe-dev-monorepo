# 052 · Shared costs from bank statements

A parent uploads their own bank and card statements on CoParent's Expenses page
(`/expenses/statements`). CoParent reads the spending, a model suggests which transactions look
like children's costs the co-parents would usually share, and the parent approves each one by
turning it into an ordinary expense, which the other parent is then asked to agree to.

## What it reads

| Bank | File | How it is recognised | Money out is |
| --- | --- | --- | --- |
| Starling | CSV | `Counter Party` and `Amount (GBP)` columns | a negative amount |
| Monzo | CSV | `Transaction ID`, `Name`, `Amount` columns | a negative amount |
| American Express | CSV | `Date`, `Description`, `Amount` columns | a **positive** amount |
| Santander current account | TXT (Windows-1252) | first line `From:`, then `Date:` blocks | a negative amount |
| Santander credit card | TXT, tab columns | a `Card no.` / `Money in` / `Money out` header | the last column |

Parsing is deterministic (`StatementParser`): amounts and dates never pass through a model.
Money in is counted and dropped before anything is stored. A row in another currency is
unreadable, never converted.

## Storage: no statement, one fingerprint per transaction

- Every spending row gets a fingerprint: SHA-256 of the file's own fields (date, payee,
  reference, amount, and the running balance where the bank gives one) plus a counter that
  tells identical same-day rows apart. Monzo rows use Monzo's transaction id.
- `statement_transactions` is unique on `(ownerParentId, fingerprint)`. That index is the
  whole of idempotency: re-uploading a statement adds nothing, sends nothing to the model, and
  a transaction can be logged once.
- A row the model did not suggest is stored as its fingerprint alone (`checked`). A suggestion
  waiting for the parent keeps the row's details (`pending`). Deciding drops them: a dismissed
  or logged row keeps only the merchant name (and the category it was logged as) so later
  suggestions can follow the parent's earlier choices.
- `statement_uploads` holds counts and dates per upload, never the file or its name.
- Both collections are in the CoParent backup: without the fingerprints a restored system
  would offer every old transaction again.

## Privacy

Everything is scoped to the parent who uploaded it. The other parent sees an expense only once
one is created from a transaction. Messages to the model carry the Langfuse
`SUPPRESS_CONTENT` flag. The model is told first names, ages and schools of the children and
both parents' first names (so a payment naming the other parent reads as between adults).

## Flow

1. `POST /statements` (multipart) parses the file and returns every spending row with its
   state, or `upload: null` when every row was seen before.
2. The page sends the never-seen rows back in batches of 40, three at a time, to
   `POST /statements/uploads/{id}/check`. No request runs long and nothing waits on the server
   to be classified. Each call is bounded at 45 seconds; a failed batch is finished by
   uploading again.
3. `GET /statements/transactions?status=suggested|dismissed|logged` feeds the tabs.
4. `POST /statements/expenses` turns a suggestion, or any row the page is showing, into a paid
   expense. **The parent chooses who paid**, since a joint account's payments can belong to
   either parent. The transaction is claimed (compare-and-set) before the expense is created
   and released if creation fails.
5. `POST …/{id}/dismiss` marks one not shared; `DELETE …/{id}` forgets that decision.

The upload and review screens also warn when a family expense with the same amount within
three days already exists. The check is family-wide because both parents may upload a joint
account's statement.

## Switches

`coparent.statements.ai-enabled` (`COPARENT_STATEMENTS_AI_ENABLED`, defaulting to
`COPARENT_ASSISTANT_ENABLED`) and `coparent.statements.model` (`COPARENT_STATEMENTS_MODEL`,
defaulting to the assistant's model). With suggestions off, uploads still work and any row can
be logged by hand.

## Known limits

- The "Latest upload" list exists only while the parent is on the page.
- Fingerprints assume a statement holds whole days, which every supported export does. An
  export cut mid-day could renumber identical same-day rows.
- The model still sometimes guesses at a truncated payee name despite being told not to (a cut-off
  "... College Ho" standing order came back as school fees). Marking it not shared teaches it for
  next time.
