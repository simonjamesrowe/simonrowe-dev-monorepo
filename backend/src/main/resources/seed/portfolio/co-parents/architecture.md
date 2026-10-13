## Under the hood

CoParent started as a separate NestJS app and now runs inside this site's Spring Boot backend, on
the same Raspberry Pi as everything else. It is a product of its own inside a shared process: its
own MongoDB database, its own switch, its own hostname and its own sign-in client, so it can be
turned off without touching the portfolio or Term Time.

![The CoParent PWA signs in with Auth0 and calls /api/coparent through nginx at coparents.simonrowe.dev. Inside the shared Spring Boot backend, the CoParent module has its own switch and caches nothing; an access policy takes the parent from the token, then calendar, messages and expenses do the work. Expenses write to CoParent's own MongoDB database and a private receipt store. Quick add and statement suggestions call OpenAI for proposals only. Brevo sends invitations and agreement emails.]({{media:architecture.svg}})

- **Backend:** Java 25, Spring Boot 4.1 and Spring AI 2.0, in the same process as the rest of the site
- **Storage:** a separate `coparent` MongoDB 8 database, bound to its own `MongoTemplate`
- **Frontend:** React 19 and Vite, a third entry point beside the site and Term Time, installable as a PWA
- **Sign-in:** Auth0, with a client of its own and a verified-email claim
- **AI:** OpenAI through Spring AI, tool calls for Quick add and JSON-schema output for statements
- **Mail:** Brevo, best-effort and switched off locally
- **Backups:** in the site's nightly backup, receipts included

## The parent comes from the token

No request names the parent making it. Every endpoint resolves the parent from the signed-in token
and checks they belong to the family in the path; a family you are not in answers 404, so its
existence is not confirmed either. Responses are never cached, by the browser or by the service
worker.

## A write that knows what you saw

An expense carries a version. Every change reads it, checks the rule for that change, and replaces
the document only if the version is still the one the parent was looking at. Production MongoDB is
a single node with no transactions, and this conditional replace is what makes two parents acting
at once safe.

```java
// backend/src/main/java/com/simonrowe/coparent/expense/ExpenseService.java
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
```

Money is whole pence in a `long`, and pounds only. The parent who did not pay owes their share
rounded half up, and the payer keeps the remainder, so the two shares always add up to the amount.

```java
// backend/src/main/java/com/simonrowe/coparent/expense/ExpenseMath.java
static long percentOf(final long amountPence, final int percent) {
  return (amountPence * percent + 50) / 100;
}
```

## Tools that do nothing

Quick add asks the model for function calls, because a typed call per action is exactly the shape
a proposal card needs. But a model with tools is a model that can act, so every tool is registered
with a callback that refuses to run. The response is read for the calls it contains, unknown tool
names are rejected, and more than 25 actions is treated as a failure.

```java
// backend/src/main/java/com/simonrowe/coparent/assistant/AssistantInferenceService.java
private static ToolCallback tool(
    final String name, final String description, final String schema) {
  return FunctionToolCallback.builder(name, (Map input) -> {
    throw new IllegalStateException("Proposal callbacks must never execute");
  }).description(description).inputType(Map.class).inputSchema(schema).build();
}
```

The call runs with strict schemas, `toolChoice("required")` and parallel tool calls, and the system
prompt treats the pasted text as data, never instructions. Prompts are marked so their content
never reaches the tracing pipeline.

## A fingerprint instead of a statement

Each bank has its own parser, and each row it reads becomes a SHA-256 fingerprint of the bank, the
card's last four digits, the row's own fields and how many identical rows came before it. A unique
index on the parent and the fingerprint is the whole of "uploading the same file again adds
nothing", and it is why the file never needs to be kept.

```java
// backend/src/main/java/com/simonrowe/coparent/statement/StatementParser.java, condensed
static String fingerprint(final StatementFormat format, final String lastFour,
    final String key, final int occurrence) {
  final String material = String.join("|", "v1", format.name(),
      lastFour == null ? "" : lastFour, key.toLowerCase(Locale.UK), String.valueOf(occurrence));
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest(material.getBytes(StandardCharsets.UTF_8)));
}
```

## Receipts stay private

Uploads on this site are served without any authorisation, so receipts never go near them. They
are written to their own volume, typed from their first bytes rather than their name, and handed
back only after a membership check, with `nosniff` and a sandboxing content security policy. The
browser strips location data from a photo before it is sent.

## Carried over, not rewritten

The original app's data moved with every id preserved, by a change unit that can run again and
stops at the first document that disagrees. Its nine end-to-end suites became Java integration
tests and were the measure of parity. Its interface kept its look: the Tailwind classes it used
were compiled once into plain CSS scoped to the app, and new screens are hand-written BEM.
