## One family, two homes

A family is two parents and their children. One parent sets it up, adds the children and invites
the other, and both then see the same calendar, the same conversations and the same balance.
Everything shown here is a synthetic family, Alex and Sam with Mia and Leo.

| Area | What it holds | What the other parent does |
| --- | --- | --- |
| Dashboard | Who has the children today, the next handover, and everything waiting on you | Sees the same day from their side |
| Calendar | Custody days, school, clubs, appointments and holidays, repeating where they repeat | Approves or declines a swap, an extension or an extra day before it changes the calendar |
| Messages | Conversations, and formal permission requests such as a sleepover or a trip | Replies, or approves or denies the request |
| Expenses | Costs already paid and costs coming up, with receipts | Agrees or disputes, and confirms money paid back |
| Statements | Suggestions from an uploaded bank export | Nothing: suggestions are private until one becomes an expense |
| Quick add | Typed proposals from a pasted message or a photo | Nothing: proposals are private to the parent who asked |

The co-parent does not have to have joined yet. Expenses and messages can be logged against an
invited parent, and they are waiting for them, unread, when they accept.

## One rule decides every agreement

Whoever last changed the terms of an expense has agreed to them, and the other parent must agree
or dispute. Logging a cost, editing the amount, changing the split or naming who paid all count as
changing the terms. Only paid, agreed expenses count towards the balance, so a cost one parent
logged on their own never moves money on paper.

Paying back is one expense at a time: the parent who owes marks it paid back, and the parent who
paid confirms it arrived. Settling up does several at once and records the bank transfers that
paid them, which have to add up to exactly the expenses ticked.

Every change carries the version the parent was looking at. If the other parent edits an expense
while you read it, agreeing is refused and the page reloads the new terms, so nobody can agree to
£45 at the moment it becomes £450.

## Quick add: the AI proposes, a parent decides

![A pasted message or photo goes to the model with a minimal family context. The model calls propose tools that do nothing; each call becomes a typed proposal card with a revision and a snapshot of its target. A parent edits, approves or rejects each card, and an approved card is written through the same services as the rest of the app, exactly once.]({{media:quick-add.svg}})

Paste a message the way it arrived, or attach a photo of a letter, and Quick add returns one card
per thing in it: an event, a weekend swap, an expense, a message to send, money paid back. Each
card can be edited before it is approved, and each is approved on its own. There is no "approve
all".

- **The model writes nothing.** It is given fifteen `propose_` tools, and every one of them throws
  if it is ever called. The calls are read as proposals, never executed.
- **It sees as little as it can.** Names, ids, categories and the family's events and open
  expenses, so it can say "the swimming lesson" and mean one. No medical notes, and no message
  bodies.
- **Nothing it reads is kept.** The pasted text and the image are not stored, and the proposals
  expire after seven days.
- **A stale card cannot land.** Each card records the state of what it changes. If the event or
  expense has moved since, approving it is blocked rather than applied to something else.
- **It cannot double up.** Approving a card twice, or retrying one that timed out, writes it once.
- **Money is pounds.** A note that says $40 becomes a card that cannot be approved until a parent
  chooses what it was in pounds. Nothing is converted.

## A bank statement, read and not kept

![A bank export from Starling, Monzo, Amex or Santander is read by a parser written for that bank, with no model involved, and money in is dropped. Each row gets a one-way fingerprint, unique to the parent who uploaded it. Only new rows go to the model, which suggests shared costs from the merchant, amount and date. The parent picks who paid and for which child, or dismisses the suggestion, and the result is an ordinary expense for the co-parent to agree.]({{media:statements.svg}})

Shared costs hide in bank statements: the school shoes, the swimming term, the trip paid through
the school's app. Upload an export and CoParent suggests which rows look like the children's.

- The file is read by a parser for that bank's own layout. Amounts never pass through a model.
- Each row is kept only as a fingerprint, so uploading the same file twice adds nothing, and the
  statement, its name and its other rows are never stored.
- Suggestions are private to the parent who uploaded them. The co-parent sees a cost only once it
  is turned into an expense.
- The model knows what the family already shares, so if the mortgage is logged as shared, the next
  mortgage payment is suggested too.

## What it does not do

It is a personal project for one family, not a product with sign-up, and it is not a legal record.
Documents and the timeline are placeholders. Email notifications are best-effort: a failed send is
logged, never retried, and never blocks the change it was about.
