# 051: CoParent shared expenses

Status: **implemented in one pull request**, at Simon's request (2026-10-08): the API, the
Expenses page and dashboard, receipts, and Quick add for expenses. Section 6 still describes
the four parts in the order they were built.

- Interactive mockup: [`mockup/index.html`](mockup/index.html) — open it in a browser. The bar along
  the top switches the signed-in parent (Alex/Sam), device, theme, and resets the data. Everything
  runs in the page; nothing is saved.
- A narrated walkthrough of the mockup was recorded with the `demo-record` skill
  (`~/workspace/simonjamesrowe/demos/coparent-expenses/`); it is not committed (18 MB).

## Context

The CoParent app (`com.simonrowe.coparent`, `frontend/src/coparent`) has a placeholder `/expenses` page (`pages/ExpensesPage.tsx`) and no backend. The dashboard already has slots for expenses, but each one is hard-coded to zero or `[]`: the `Monthly Spend £0` widget, a "Budget snapshot" panel, `approvalsSummary.byType.expenses: 0`, and an "Add Expense" quick action that only navigates. Spec 048 deliberately left expenses out of scope.

Three earlier designs exist (Design OS budgeting, `coparent-specs/designs/expenses.html`, `shared-types`). They disagree with each other, use USD, and none has a working split/settle model. This plan replaces all three.

**Decisions from Simon (2026-10-08)**

- An expense is either **already paid** or **upcoming**.
- **Exactly one parent pays**, or will pay. For an upcoming expense the payer can be "not decided yet".
- "Who paid" and "how it's shared" are separate. The share defaults to **50/50** and can be changed per expense to "one parent covers all" or a custom percentage. The app keeps a running balance.
- The **other parent accepts or disputes** an expense before it counts towards the balance.
- Balances are cleared by **marking individual expenses as reimbursed**. A "Settle up" sheet does this for several expenses at once.
- **Receipts are in v1.** They go in private, family-scoped storage that is never served from the public `/uploads`.
- Notifications are **in-app and email**, the email going through the existing mailer behind `coparent.email-enabled`.

---

## 1. Domain model

### Collection `expenses` (coparent DB) and record `coparent/model/Expense.java`

| Field | Notes |
|---|---|
| `id`, `familyId` | `ObjectId` |
| `title` | 1–120 chars |
| `category` | fixed set (like `PERMISSION_TYPES`): `education, clothing, medical, activities, childcare, travel, food, other` |
| `childIds` | at least one, each validated against the family with `countByIdInAndFamilyIdAndDeletedAtIsNull` |
| `amountPence` | `long`, from 1 to 10,000,000 (£100k cap). There are no floats anywhere. |
| `currency` | `"GBP"`, stored now so multi-currency is possible later |
| `timing` | `paid` or `upcoming` |
| `date` | `LocalDate`. It is `paidOn` when paid and `dueOn` when upcoming. |
| `payerId` | Required when `paid`. Nullable when `upcoming` ("not decided"). |
| `shares` | `[{parentId, percent}]`: exactly the two active parents, integers that sum to 100 |
| `agreement` | `{status: pending\|agreed\|disputed, requestedBy, respondedBy, respondedAt, note}` |
| `reimbursement` | `{status: none\|outstanding\|claimed\|reimbursed, claimedBy, claimedAt, settledBy, settledAt, note}` |
| `receipts` | embedded `[{id, contentType, sizeBytes, displayName, uploadedBy, uploadedAt}]`, at most 5 |
| `notes` | up to 1000 chars, optional |
| `history` | embedded `[{at, by, action, note}]`, capped at 50 with `$push` + `$slice`. It feeds the detail timeline, since there is no audit read API. |
| `version` | `long`, incremented on every write. This is the optimistic-concurrency guard (see §2). |
| `createdBy`, `createdAt`, `updatedAt`, `deletedAt`, `assistantActionId` | `assistantActionId` is reserved for a later `CREATE_EXPENSE` assistant action |

### Money rules (`ExpenseMath`, a pure function with parameterised tests, mirrored in `frontend/.../expenses/money.ts`)

- The **debtor** is the parent who did not pay. The debtor's amount is `owed = (amountPence × debtorPercent + 50) / 100`, which is half-up rounding. The payer's share is `amount − owed`, so the two shares always add up to the full amount.
- **Net balance** is the sum of `owed` over expenses that are paid, agreed, and `outstanding` or `claimed`, signed by direction. Each response returns `owedPence`, `debtorParentId` and `creditorParentId`, so the UI never does money arithmetic of its own beyond the form preview.
- If `owed` is 0 (the payer covers 100%), reimbursement is `none`, shown as "No reimbursement needed".

### Lifecycle (one rule drives everything)

> **Whoever last changed an expense's terms has agreed to them. The other parent must respond.**

The terms are the amount, the share, and the payer once one has been decided.

```
create / edit terms ──► agreement: pending (requestedBy = actor) ──► agree ──► agreed
                                         │                                     │
                                         └──► dispute(note required) ──► disputed ──► requester edits → pending
                                                                                   └► requester withdraws (delete)

paid + agreed + owed>0 ─► reimbursement: outstanding ─► debtor "Mark paid back" ─► claimed ─► creditor "Confirm received" ─► reimbursed
                                    │                                          └► creditor "Not received yet" ─► outstanding
                                    └─► creditor "Mark as reimbursed" (cash etc.) ─────────────────────────────► reimbursed
upcoming ─► "Mark as paid" {payerId, paidOn, amountPence}
            same amount and same/previously-undecided payer → stays agreed; anything else → pending again
```

**Who can do what**

| Action | Who | When |
|---|---|---|
| Edit | Either parent | Not `claimed`, not `reimbursed`. Changing the terms resets agreement to `pending` with the editor as requester, and resets reimbursement to `none`. |
| Delete | Upcoming: either parent (no money has moved; recorded in audit) | Any time |
| Delete | Paid: requester only | While `pending` or `disputed` |
| Delete | Agreed paid expense | Never. Edit it instead. |
| Add receipt | Either parent | Any state except deleted |
| Remove receipt | The uploader | Not reimbursed |
| Agree / dispute | The non-requester only | Requester gets 403 |

`reimbursed` expenses are immutable.

---

## 2. Backend

### New package `com.simonrowe.coparent.expense`

This mirrors `calendar/` and `messaging/`.

**`ExpenseController`** lives under `/api/coparent/families/{familyId}/expenses`. It uses nested request/response records with `values()` and `from()`, and parses ids with `CoparentIds.parse`.

| Endpoint | Request | Notes |
|---|---|---|
| `GET` (list) | none | Every non-deleted expense, sorted by `date` descending. Family volume is small, so there is no paging in v1. |
| `GET /summary` | none | See below |
| `GET /{id}` | none | |
| `POST` | full body | Returns 201 |
| `PUT /{id}` | full body + `version` | Full replacement. Needs a test that every field round-trips (CLAUDE.md rule). |
| `POST /{id}/mark-paid` | `{payerId, paidOn, amountPence, version}` | |
| `POST /{id}/agree` | `{version}` | |
| `POST /{id}/dispute` | `{note, version}` | |
| `POST /{id}/reimbursement/claim` | `{note?, version}` | |
| `POST /{id}/reimbursement/confirm` | `{version}` | |
| `POST /{id}/reimbursement/reject` | `{note, version}` | |
| `POST /{id}/reimbursement/mark` | `{version}` | The creditor settles in one step |
| `POST /settle` | `{items: [{id, version}]}` | Applies `claim` where the actor is the debtor and `mark` where the actor is the creditor. Each item is its own conditional update, so there are no transactions, and it returns a result per item. |
| `DELETE /{id}` | none | Returns 204 |
| `POST /{id}/receipts` | multipart `file` | |
| `GET /{id}/receipts/{receiptId}` | none | |
| `DELETE /{id}/receipts/{receiptId}` | none | |

- **`version` is required on every transition.** Without it, Sam could press Agree on the £45 version at the same moment Alex edits it to £450, and the agreement would apply to £450.
- `/summary` returns `{currency, balance:{netPence, debtorParentId, creditorParentId, outstandingCount, awaitingConfirmationPence}, needsYourAction:{count, totalPence}, awaitingOther:{count}, upcoming:{count, totalPence, yourSharePence, next:[up to 3 expenses due within 30 days]}, thisMonth:{totalPence, yourSharePence, byCategory:[{category,totalPence}]}}`. The service computes it from the family's list.

**`ExpenseService` (CRUD and validation) and `ExpenseTransitions`**

- Every method starts with `access.requireMember(familyId)`. A non-member gets 404, not 403.
- The other parent is resolved from `parents.findByFamilyIdAndStatus(familyId, "active")`. If the family has no second parent, return 409 "Invite your co-parent before adding shared expenses".
- Every state change is **one conditional `findAndModify`** guarded on `_id`, `familyId`, `deletedAt: null`, `version` and the expected state and party. On a miss it re-reads the expense and answers 403 (wrong party), 409 "This expense has changed, reload" (version or state), or 404. This copies `CalendarService.resolveChange`.
- Validation:
  - Request shape (`@NotBlank`, `@Size`, `@Positive`, `@Max`) uses `@Valid` bean validation, which gives the drawer `fieldErrors`.
  - Cross-field checks are hand-written `ResponseStatusException(BAD_REQUEST)`: payer required when paid; shares cover exactly the two active parents and sum to 100.
  - Dates are checked in the family's `timeZone`: within ±2 years, and a paid date must not be after today.
- Audit: `audits.record(familyId, "expense", id, action, Map.of("amountPence",…, "timing",…, "category",…, "agreement",…))`. Actions are `create`, `update`, `agree`, `dispute`, `mark_paid`, `claim`, `confirm`, `reject_claim`, `mark_reimbursed`, `delete`, `receipt_add` and `receipt_remove`. **No title, notes or receipt names go into audit**; this follows the "safe summaries" Javadoc on `CoparentAuditService`.
- Each mutation has an overload taking `(presetId, assistantActionId)`, matching calendar, so a later assistant action plugs in through the five places the exploration identified.

**`ExpenseMath`**: share and owed calculation, and the summary fold.

**`ExpenseMailer`** is modelled on `invitation/InvitationMailer`:
- Best-effort. It is a no-op unless `coparent.email-enabled`, catches `MailException` and logs it, and never fails the request.
- It sends after the DB write for three events:
  - New terms needing agreement go to the other parent: "Alex added *School shoes*, £45.00. Your share is £22.50. Review: `{appUrl}/expenses?expense={id}`".
  - A dispute goes to the requester.
  - A reimbursement claim goes to the creditor.
- Agree and confirm are in-app only. The email body uses a text block with `.formatted(…)`, as checkstyle requires.

**Receipts: `CoparentReceiptStore`**, modelled on `school/ingest/SchoolAttachmentStore`:
- The root is `coparent.receipt-path` (default `coparent-receipts/`, prod `/workspace/coparent-receipts/`).
- The file path is `<familyHex>/<receiptHex>`. Nothing the user chose ever becomes part of a path, and there is a resolve, normalise and `startsWith(root)` check.
- Uploads are limited to 10 MB by the existing multipart limit. nginx allows 12 MB on both layers since termtime-note-image-upload.
- The type is **sniffed from magic bytes**: JPEG `FF D8 FF`, PNG `89 50 4E 47`, PDF `%PDF-`. Anything else, HEIC included, gets 415. The client's `Content-Type` is ignored.
- Write order:
  - Adding a receipt writes the file first, then does `$push` with the guard `receipts.4: {$exists:false}`, which caps the list at five. If the push misses, delete the file.
  - Removing a receipt does `$pull` first, then deletes the file.
  - Soft-deleting an expense keeps its files, as evidence for any dispute.
- Download first calls `requireMember`, then checks that the receipt belongs to an expense in that family. It streams with the stored content type and these headers: `Content-Disposition: inline; filename="receipt-N.ext"`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox`. `no-store` comes from `CoparentFeatureFilter`.

### Persistence, migration, backup

- `coparent/persistence/ExpenseRepository`. It must be in this package so it binds to `coparentMongoTemplate`.
- `migration/changeunits/V052CreateCoparentExpenses`, modelled on V045:
  - It takes `CoparentMongoOperations` and has a public static `createIndexes(MongoTemplate)`.
  - Indexes: `idx_coparent_expense_family_date` on `(familyId, deletedAt, date desc)`, `idx_coparent_expense_family_agreement` on `(familyId, agreement.status, deletedAt)`, and `idx_coparent_expense_receipt_id` on `receipts.id`, unique and sparse.
- `dataops/BackupService`:
  - Add `"expenses"` to `COPARENT_BACKUP_COLLECTIONS`.
  - Zip the receipts directory under `coparent-receipts/`, the same way `school-attachments/` is zipped.
- `dataops/RestoreService`:
  - Add the collection to `COPARENT_IMPORT_ORDER`, after children.
  - Call `V052…createIndexes` from `ensureCoparentIndexes()`.
  - Add a `restoreCoparentReceipts` step with the same zip-slip guard as `restoreSchoolAttachments`.
- `test/.../dataops/CoparentBackupCoverageTest`: add the collection and the index-name assertions.
- `application.yml`: `coparent.receipt-path: ${COPARENT_RECEIPT_PATH:coparent-receipts/}`, plus the new field in `CoparentProperties`.
- `docker-compose.prod.yml`: on `backend`, add `COPARENT_RECEIPT_PATH` and a `coparent-receipts:/workspace/coparent-receipts` named volume. Like `school-attachments`, it must not go inside `backend-uploads`.

---

## 3. UI design

The coparent CSS is a **frozen compiled Tailwind blob**, so new utility classes silently do nothing.
- All new UI uses **BEM classes `expense-*`**, written in the hand-written coparent block of `frontend/src/styles.css`. This follows the newest code (`.assistant-*`), scoped under `.coparent-app`, with a `prefers-color-scheme: dark` variant.
- Palette:
  - Teal `#0d9488` is the primary colour.
  - Rose marks "you owe" and disputes.
  - Amber marks "needs your action" and overdue.
  - Emerald or muted teal marks settled.
  - Parents are shown as violet (primary) and sky (co-parent), using the existing fallback `p.color || (role==='primary'?'violet':'sky')`.
- Extract that fallback into a shared `parentTone()` helper and a `ParentDot` component.
- Amounts always show pence: `Intl` GBP with 2 decimals. The dashboard's existing `formatCurrency` rounds to whole pounds, so it is not reused for expenses.
- Icons are lucide: `GraduationCap, Shirt, Stethoscope, Trophy, Baby, Plane, UtensilsCrossed, Receipt`.

### 3.1 Expenses page (`/expenses`), desktop

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Expenses                                                  [ + Add expense ]  │
│ Shared costs for Mia and Leo                                                 │
│                                                                              │
│ ┌─────────────────────────────┐ ┌───────────────────────┐ ┌────────────────┐ │
│ │ BALANCE                     │ │ NEEDS YOUR OK         │ │ COMING UP      │ │
│ │ Sam owes you                │ │ 2 expenses · £139.98  │ │ £240.00        │ │
│ │ £72.50          (teal)      │ │ [ Review ]            │ │ 3 due ≤ 30 days│ │
│ │ across 3 expenses           │ │                       │ │ your share     │ │
│ │ £30.00 awaiting your confirm│ │ 1 waiting for Sam     │ │ £120.00        │ │
│ │ [ Settle up ]               │ │                       │ │                │ │
│ └─────────────────────────────┘ └───────────────────────┘ └────────────────┘ │
│                                                                              │
│ (Needs action 2)( Upcoming 3 )( Owed 3 )( Settled )( All )   Child ▾  Type ▾ │
│                                                                              │
│ NOVEMBER 2026 ───────────────────────────────────────────────────────────────│
│ [👟] School shoes                                  £45.00  ● Waiting for Sam │
│      Mia · Clothing · ●Paid by you · 3 Nov · 📎1    Sam's share £22.50        │
│ ─────────────────────────────────────────────────────────────────────────────│
│ [🏂] Ski trip deposit                            £240.00   ◐ Needs your OK   │
│      Leo · Activities · Due 20 Nov · ●Sam will pay  Your share £120.00       │
│                                                       [ Dispute ] [ Agree ]  │
│ ─────────────────────────────────────────────────────────────────────────────│
│ [🩺] Dentist check-up                             £60.00   ○ You owe Sam £30 │
│      Mia · Medical · ●Paid by Sam · 1 Nov                 [ Mark paid back ] │
│ ─────────────────────────────────────────────────────────────────────────────│
│ [🎓] Swimming term                                £80.00   ◑ Sam says paid   │
│      Mia, Leo · Activities · ●Paid by you · 28 Oct     [ Confirm received ]  │
└──────────────────────────────────────────────────────────────────────────────┘
```

- The emoji in the sketch stand in for lucide icons in a category-tinted tile. The ● next to "Paid by" is the parent's colour dot.
- **Default tab** is "Needs action" when its count is above 0, otherwise "All". It is kept in the URL as `?view=`.
- "Needs action" contains: pending items where you are not the requester, disputed items where you are the requester, outstanding items where you are the debtor, and claimed items where you are the creditor.
- Rows group under month headers by `date`, newest first. The Upcoming tab sorts by due date, earliest first.
- Clicking a row opens the detail drawer (`?expense=id`, deep-linkable from email). Inline buttons cover only the one obvious next step.
- **Empty states:**
  - No co-parent: "Invite your co-parent to start sharing costs" with a link to `/family-setup`.
  - No expenses yet: "Add your first expense" with the add button.
  - A tab with nothing in it: one quiet line.

**Mobile (below `lg`)**
- The Balance card goes full width. The other two cards sit side by side underneath.
- The tabs become a horizontally scrolling chip row. Filters collapse into one "Filter" button.
- Each row stacks as title/amount, then meta, then status chip, then the action button full width.
- "+ Add expense" sits in the page header. The drawers become full-width sheets.

### 3.2 Status chip (computed by `expenseStatus.ts` from the signed-in parent's point of view)

| State | Your view | Tone | Your action |
|---|---|---|---|
| pending, you requested | Waiting for Sam | slate | none (Edit / Withdraw in detail) |
| pending, Sam requested | Needs your OK | amber | Agree / Dispute |
| disputed | Sam disputed / You disputed | rose | requester: Edit or Withdraw |
| agreed, upcoming | Agreed · due 20 Nov (Overdue if past) | teal / amber | Mark as paid |
| outstanding, you're debtor | You owe Sam £30.00 | rose | Mark paid back |
| outstanding, you're creditor | Sam owes you £22.50 | teal | Mark as reimbursed |
| claimed, you're creditor | Sam says paid · confirm | amber | Confirm received / Not received |
| claimed, you're debtor | Paid back · awaiting Sam | slate | none |
| reimbursed / none | Settled / No reimbursement needed | emerald | none |

### 3.3 Add/Edit expense drawer (Vaul right drawer, `max-w-lg`, with the required `coparent-app` wrapper inside the portal)

```
┌ Add expense ───────────────────────────────── ✕ ┐
│ What was it for?                                 │
│ [ School shoes                                 ] │
│ Amount                  Category                 │
│ [ £  45.00        ]     [ 👕 Clothing        ▾ ] │
│ For                                              │
│ ( ✓ Mia )  ( Leo )                               │
│                                                  │
│ Has it been paid?                                │
│ [ ✓ Already paid  |  Coming up ]                 │  ← segmented control
│ Who paid?                        Paid on         │  ← upcoming: "Who will pay?" +
│ [ ● You (Alex) | ● Sam ]         [ 03/11/2026 ]  │    "Not decided yet"; "Due by"
│                                                  │
│ How should it be shared?                         │
│ [ ✓ 50 / 50 ][ You cover it ][ Sam covers it ][ Custom ] │
│   Custom:  You [ 60 ]%  ·  Sam 40%               │
│                                                  │
│ ┌──────────────────────────────────────────────┐ │
│ │ You paid £45.00. Your share is £22.50.       │ │  ← live summary, the
│ │ Sam will owe you £22.50 once Sam agrees.     │ │    point of the form
│ └──────────────────────────────────────────────┘ │
│ Receipts (optional)                              │
│ [ 📷 Add photo or PDF ]  [thumb ✕] [thumb ✕]     │
│ Notes (optional)                                 │
│ [                                              ] │
│ ⓘ Sam will be asked to agree, and emailed.       │
├──────────────────────────────────────────────────┤
│                         [ Cancel ] [ Add expense ]│
└──────────────────────────────────────────────────┘
```

- Built on the `ComposeDrawer` pattern: `useState` fields, reset on open, a computed `valid` that disables Save, server `fieldErrors` shown under their fields, and the banner from `apiErrorMessage`.
- Default values:
  - "Already paid", today's date, payer = you, share 50/50.
  - "For" pre-selects the child when there is only one.
- The amount input accepts `^\d{1,6}(\.\d{1,2})?$`. That regex is anchored and bounded, so it cannot backtrack, and the input is converted to pence before sending.
- The live summary sentence covers every combination, including:
  - "Sam will pay £240.00 when it's due. Your share will be £120.00."
  - "You covered all of it, so nothing is owed."
  - "Sam paid £60.00. You'll owe Sam £30.00."
- Editing an agreed expense shows a warning: "Changing the amount, share or payer asks Sam to agree again."
- Receipts:
  - Images are downscaled in the browser by reusing `frontend/src/pages/admin/schoolNoteImage.ts` (EXIF orientation, 2000 px longest edge, JPEG). Re-encoding **strips EXIF and GPS**. PDFs upload unchanged.
  - Uploads happen after the expense is created, one at a time. If one fails, the expense is kept and a toast says "Expense saved. 1 receipt didn't upload. Retry from the expense."

### 3.4 Expense detail drawer

```
┌ School shoes ──────────────────────────────── ✕ ┐
│ £45.00                       [● Needs your OK ]  │
│ Clothing · Mia                                   │
│ ──────────────────────────────────────────────── │
│ Paid by        ● Alex · 3 Nov 2026               │
│ Shared         50 / 50                           │
│ Alex's share   £22.50     Sam's share £22.50     │
│ Sam owes Alex  £22.50 once agreed                │
│ Receipts       [thumb] [PDF ▸]                   │  ← fetched as blob with bearer
│ Notes          "Clarks, the ones she picked"     │    token → object URL (revoked
│ ──────────────────────────────────────────────── │    on close/unmount)
│ History                                          │
│ • 3 Nov 18:04  Alex added it                     │
│ • 4 Nov 09:12  Sam disputed: "We said trainers"  │
│ • 4 Nov 19:30  Alex changed the amount to £45.00 │
├──────────────────────────────────────────────────┤
│ [ Discuss ]              [ Dispute ] [ Agree ]   │  ← footer = actions for state (§3.2)
└──────────────────────────────────────────────────┘
```

- **Dispute** opens an inline note field, which is required.
- **Discuss** opens `ComposeDrawer` with the subject pre-filled as "About: School shoes (£45.00)". This needs a small new `initialSubject` prop.
- Edit, Withdraw/Delete and "Mark as paid" sit in a `⋯` menu, and each appears only when the §1 rules allow it.

### 3.5 Settle-up drawer (from the Balance card)

```
┌ Settle up with Sam ──────────────────────────── ✕ ┐
│ Overall, Sam owes you £72.50                       │
│ ☑ Swimming term     Sam owes you         £40.00    │
│ ☑ School shoes      Sam owes you         £22.50    │
│ ☑ Dentist check-up  you owe Sam          £30.00    │  ← listed with the opposite sign
│ ☑ Uniform           Sam owes you         £40.00    │
│ ────────────────────────────────────────────────── │
│ Net: Sam pays you £72.50                           │
│ Ticking marks Sam's debts as reimbursed and yours  │
│ as paid back (Sam confirms).                       │
│                          [ Mark selected settled ] │
└────────────────────────────────────────────────────┘
```

This calls `POST /settle`, which handles netting across both directions while still settling each expense individually.

### 3.6 Navigation

`NavigationItem` in `App.tsx`/`MainNav.tsx` gains an optional `badge`. The Expenses item shows `summary.needsYourAction.count`, read via `useExpenseSummary` for the active family. While on Expenses, change its icon from `DollarSign` to `PoundSterling`.

---

## 4. Dashboard changes (`pages/DashboardPage.tsx`, `components/dashboard/DashboardOverview.tsx`, `types/dashboard.ts`)

```
Widget grid:  [ Upcoming events (lg) ] [ Pending approvals (md) ] [ Balance (md) ] [ Unread (sm) ] …
                                        └ includes expenses       └ "Sam owes you £72.50" teal /
                                                                    "You owe Sam £30.00" rose /
                                                                    "All square" → /expenses?view=owed
┌ Expenses ─────────────────────────────── Open expenses ┐   replaces "Budget snapshot"
│ Sam owes you £72.50 · 3 expenses        [ Settle up ]  │
│ Needs your OK                                          │
│  Ski trip deposit · £240.00 · your share £120  [Agree] │  ← up to 3; Agree inline, others open
│ Coming up (30 days) · your share £120.00               │     /expenses?expense=id
│  Ski trip deposit · due 20 Nov · Sam will pay          │
│ This month £312.40 · your share £156.20                │
│  Activities ████████░░ £180  Clothing ███░ £45 …       │  ← existing bar markup, relative to the
└────────────────────────────────────────────────────────┘     largest category (no budgets in v1)
```

- **Types:**
  - Replace `BudgetSummary`/`BudgetCategory` with an `ExpenseSummary` type that mirrors `/summary`.
  - Replace the dashboard `Expense`/`ExpenseStatus` with the API type.
  - Remove the `wid-spend` widget and add a `wid-balance` widget.
- **Approvals:**
  - `approvalsSummary.byType.expenses = summary.needsYourAction.count`, which also feeds the hero's approvals pill.
  - The Pending approvals panel lists expense items, such as "Sam added School shoes, £45.00, your share £22.50", and wires `onViewExpense` to `/expenses?expense={id}`.
- **Quick action:** "Add Expense" gets the helper text "Log a cost or snap a receipt" and navigates to `/expenses?new=1`, which opens the drawer.
- New markup uses `expense-*` BEM classes, plus utilities that already exist in the blob.

---

## 5. Frontend files

- **`lib/api/client.ts`:**
  - Types `ApiExpense`, `ApiExpenseSummary` and the request types.
  - A blob GET for receipts.
- **`hooks/api/useExpenses.ts`:**
  - `expenseKeys`, `useExpenses`, `useExpense`, `useExpenseSummary`.
  - Mutations for create, update, delete, mark-paid, agree, dispute, claim, confirm, reject, mark-reimbursed, settle, upload receipt and delete receipt.
  - Every mutation invalidates the list and summary keys.
  - All of it is re-exported from `hooks/api/index.ts`.
- **`components/expenses/`:**
  - `ExpenseSummaryCards`, `ExpenseTabs`, `ExpenseList`, `ExpenseRow`, `ExpenseStatusChip`
  - `ExpenseFormDrawer`, `ExpenseDetailDrawer`, `SettleUpDrawer`
  - `ReceiptPicker`, `ReceiptThumb`
  - `money.ts`, `expenseStatus.ts`, `categories.ts`
  - `ParentDot` and `parentTone.ts`
- **`pages/ExpensesPage.tsx`**: the real page. It handles the `?view`, `?expense` and `?new` URL parameters.
- **`styles.css`**: the `expense-*` block, which needs to be measured against the coparent preflight in both colour schemes.
- **Tests:**
  - Remove the Expenses row from `PlaceholderPages.test.tsx`.
  - Add `/expenses` to `e2e/coparent.local.spec.ts`.

---

## 6. Delivery: three PRs, each behind `coparent.enabled`, each shipped with `pr-review-loop`

0. **Mockups.** Done: the interactive HTML mockup in [`mockup/`](mockup/index.html) covers the
   Expenses page, the add/edit drawer, the detail drawer, Settle up, Quick add, the dashboard,
   mobile and dark mode. It replaces the planning-session Excalidraw wireframes. PR 2 waits on
   Simon's sign-off of it.
1. **`feat: add co-parent expenses API`:**
   - The model, `ExpenseMath`, the service and transitions, the controller (no receipts yet), `ExpenseMailer`, V052, backup/restore and the coverage test.
   - `specs/051-coparent-expenses/spec.md`, containing this design.
   - A CLAUDE.md "Recent Changes" entry.
2. **`feat: add expenses page and dashboard balance`:** the whole frontend except receipts.
3. **`feat: attach receipts to co-parent expenses`:**
   - `CoparentReceiptStore` and the endpoints.
   - The receipts directory in backup/restore.
   - The prod compose volume and env.
   - `ReceiptPicker`/`ReceiptThumb`.
   - Deploy note: the compose change touches `backend`. Before merging, confirm `backend` is in `FACTORY_DEPLOY_RECREATABLE`, so `sync-config` does not hold back the deploy.

4. **`feat: let Quick add propose expenses`** (added 2026-10-08 at Simon's request; now in scope):
   - **Three new `AssistantProposalBatch.ActionType`s:**
     - `CREATE_EXPENSE`, with payload `title, amountPence, category, childIds, timing, date, payerId (either parent, or null when upcoming), sharePercent, notes`.
     - `MARK_EXPENSE_PAID`, which targets an upcoming expense.
     - `CLAIM_EXPENSE_REIMBURSEMENT`, which targets an outstanding expense the actor owes on, matched like `UPDATE_EVENT` targets with a `targetSnapshot` hint.
   - **Assistant code to extend:**
     - `AssistantInferenceService.createTools()`, plus a `SYSTEM_PROMPT` section on money. Amounts are always GBP pence. The payer comes from who the note says paid, and "I" means the signed-in parent. A missing child is a field error, never a guess.
     - `AssistantProposalService.normalize`.
     - `AssistantActionExecutor.execute`, using reconcile-then-create via `ExpenseRepository.findByAssistantActionId`.
     - `AssistantContextFactory`, so the model can see open expenses to match against.
   - **Approving runs the normal flow.** An approved `CREATE_EXPENSE` goes through the same `ExpenseService.create` overload, so the other parent still has to agree.
   - **Receipt photos:**
     - The existing `InputKind.IMAGE` path reads them.
     - The server still keeps no raw input. The *browser* holds the image, and only when the parent ticks "Attach this photo as the receipt" on the approved card does the client upload it to `POST …/expenses/{id}/receipts`.
   - **Frontend:**
     - `AssistantActionCard` gets an expense-specific body: tile, payer/child/share editors, and the same live-summary sentence as the drawer.
     - `labels`, `requiredFields` and `invalidateDomain` include the new types.
     - The Expenses page gets a "Paste a note or receipt" button, and the add drawer links to Quick add.

**Pound sterling everywhere, never US dollars.** This was requested on 2026-10-08. Every earlier design source (Design OS, `coparent-specs/designs/expenses.html`, the old `coparent-api`) used USD, and none of them is reused. The mockup and demo are already GBP-only: amounts render through `Intl.NumberFormat('en-GB', {currency:'GBP'})`, with a £ prefix on the amount input.

**Backend:**
- `currency` is always `"GBP"`. The request DTO does not accept it at all. It is stored only so multi-currency could be added later.
- Amounts are `long` pence.
- `ExpenseMailer` formats money with `NumberFormat.getCurrencyInstance(Locale.UK)`, through one `ExpenseMoney.format(pence)` helper.

**Assistant:**
- `SYSTEM_PROMPT` states that every amount is in pounds sterling.
- A note that gives an amount in another currency ("$40", "€25") produces a field error on the card. It is never silently converted or treated as pounds.

**Frontend:**
- There is one `formatMoney(pence)` in `components/expenses/money.ts`, pinned to `'en-GB'`/`'GBP'` with 2 decimals.
- The dashboard's existing `formatCurrency` (`DashboardOverview.tsx:36`) uses the browser locale and whole pounds, so expense values don't use it.
- Swap the Expenses nav icon from `DollarSign` to lucide's `PoundSterling` in `App.tsx:6,112`.
- Placeholders and copy say "£".

**Tests:**
- `money.test.ts` asserts `formatMoney(4500) === '£45.00'` and that the output never contains `$`, under an `en-US` default locale too.
- `ExpenseMailerTest` asserts `£22.50`.
- An assistant test checks that a `$` amount becomes a field error.

**Either parent can log an expense, whoever paid it.** This is already the model: `payerId` is any active parent, and the creator is only the requester for agreement. If Alex logs an expense that Sam paid, Sam still confirms it. The mockup and demo both show it.

---

## 7. Verification

- **Backend unit tests:**
  - `ExpenseMathTest`, parameterised over odd pence, 0/100/custom percentages, half-up rounding and sign.
  - Summary fold tests.
  - Validation tests in the `CalendarServiceValidationTest` style.
- **`ExpenseApiIntegrationTest`**, using the two-parent fixture from `ScheduleChangeApplyIntegrationTest.family()`:
  - Create, agree and claim/confirm, checking the balance at each step.
  - The requester agreeing gets 403. An outsider (`mallory`) gets 404. A repeat decision gets 409.
  - **A stale `version` gets 409**, specifically "agree after an edit".
  - Editing an agreed expense resets agreement. Marking an expense paid at the same amount keeps the agreement; at a different amount it resets it.
  - Deleting an agreed paid expense is refused.
  - A single-parent family gets 409.
  - PUT round-trips every field.
  - Mail is sent through a mocked `JavaMailSender` for exactly the three events, and the request still succeeds when the mailer throws.
- **Receipt tests:**
  - Magic-byte rejection with 415, including a PNG renamed `.pdf` and a renamed HEIC.
  - The sixth receipt is refused.
  - Cross-family download gets 404.
  - Response headers are correct.
  - A path-escape attempt is refused.
  - Backup → restore round-trips both a receipt file and the indexes.
- **`V052CreateCoparentExpensesTest`, `CoparentBackupCoverageTest`, and `./gradlew :backend:test checkstyleMain`**, through the `backend-test` skill.
- **Frontend unit tests:**
  - `money.test.ts` (parse, format and share preview).
  - `expenseStatus.test.ts`, covering every row of §3.2 from both parents' points of view.
  - Drawer tests: the live summary text for each combination, and that Save stays disabled until the form is valid.
  - `DashboardOverview.test.tsx`, with the fixture updated for `ExpenseSummary`.
  - Page tests mock `../hooks/api`, as in the existing pattern.
  - Then run `npm test` and `npm run lint`.
- **End to end:**
  1. Start the stack with the `local-env` skill.
  2. With Playwright, sign in as parent A and add a paid £45.00 expense split 50/50, plus an upcoming £240 expense with the payer undecided.
  3. In a second context as parent B, agree to the first and dispute the second.
  4. Back as A, edit the disputed expense. As B, agree to it, then mark it paid. As A, use Settle up. As B, confirm.
  5. At each step, check the balance card on both dashboards, the nav badge and the status chips.
  6. Screenshot desktop and mobile in light and dark into `.context/`.

---

## 8. Implementation notes

Things the build settled that the design above left open:

- **Every write is read, rule-check, then replace guarded on `version`.** `ExpenseService`
  applies each rule in Java and writes with `findAndReplace` matched on `_id`, `familyId`,
  `deletedAt: null` and the version it read. A concurrent change therefore always loses with a
  409, rather than each transition needing its own hand-written conditional update.
- **`DELETE` takes the version as `?version=`**, because a `DELETE` has no body.
- **Settle up checks each item's version before its state**, so a stale item reports `failed`
  rather than being quietly skipped.
- **A request may name `currency`, and only `"GBP"` is accepted.** Anything else gets 400
  "Expenses are in pounds sterling (GBP) only". Leaving the field unknown would mean Jackson
  silently ignored `"USD"` and the expense was stored as pounds.
- **Resending a disputed expense with no change to the terms** puts it back to pending, so the
  requester can clarify the notes and ask again.
- **Shares are stored sorted by parent id.** That fixes which parent gets the rounding penny
  while nobody is down to pay.
- **Receipts bump the expense's `version`.** Adding or removing one does not change the terms,
  but every other write replaces the whole document guarded on the version. A receipt write
  that did not bump it would be silently lost to an agree or a settle that read the expense a
  moment earlier. The cost is an occasional "this expense has changed, reload" for the other
  parent.
- **Quick add keeps the note's own currency until the parent chooses GBP.** If the server
  rewrote "$40" to GBP straight away, saving the card again without looking would turn it into
  £40.
- **A Quick add photo stays in the browser** until the parent approves the expense with "Attach
  this photo as the receipt" ticked. Only then is it uploaded, re-encoded so its EXIF and
  location data are dropped.
- **The expense styles are scoped under `.coparent-app`.** CoParent's preflight sets
  `.coparent-app button { padding: 0; background: transparent }`, which beats a single class.


## 9. Expenses before the co-parent accepts

A parent can log shared expenses as soon as they have invited their co-parent, before the
invitation is accepted. Added 2026-10-08.

- **Inviting reserves a parent row.** `InvitedParents.reserve` upserts a `parents` row with
  status `invited`, keyed on family and email, named from the invite form's optional name
  or, failing that, from the email (`rhian.jones@…` becomes "Rhian Jones"). Its `auth0Id` is a
  synthetic `invited:<id>`, because the unique `(familyId, auth0Id)` index treats a missing
  subject as null and two placeholders in one family would collide. No Auth0 subject can
  match it, since real ones contain `|`, and every access check asks for `active` anyway.
- **Accepting adopts that row** (`InvitationService.adoptInvitedParent`): same id, status
  `active`, the real subject. So every expense logged against it becomes theirs with nothing
  rewritten. A profile the invitee created at first sign-in supplies the name, colour and
  avatar and is then deleted. It belongs to no family, so nothing refers to it.
- **Only expenses see an invited parent.** `ExpenseService.couple()` falls back to it when the
  family has no other active parent. A family that already has two ignores any open
  invitation. `GET …/parents?includeInvited=true` is opt-in and is used only by the Expenses
  page, the dashboard's expense widgets and Quick add's payer list. Calendar, messaging and
  access control still read `active` parents only.
- **The rules are unchanged.** Everything logged waits for the invited parent to agree, one
  expense at a time, once they join. Nothing counts towards the balance until then.
  `ExpenseMailer` sends nothing to a parent who is not `active`.
- **Cancelling** an invitation sets the row to `uninvited`. Its expenses stay, and new ones
  are refused. **Inviting the same email again** brings the same row back, keeping what was
  logged. An expired invitation keeps its row, so resending carries on.
- **The primary parent can rename an invited parent** (`PATCH /parents/{id}/invited-name`)
  until they join. After that the name is theirs.
- **`V053ReserveInvitedCoparents`** adds the partial unique index
  `idx_coparent_parent_invited_email` (one `invited`/`uninvited` row per family and email) and
  reserves a row for every invitation still open whose email is not already a member.
  `RestoreService.ensureCoparentIndexes` re-creates the index.
