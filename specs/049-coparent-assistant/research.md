# Research: CoParent Assistant Action Proposals

## Direct model call with inert strict functions

**Decision**: Invoke Spring AI's `ChatModel` directly with per-request OpenAI options, strict function schemas, `parallelToolCalls=true`, and reasoning effort `none`. Register callbacks whose implementation throws if called, and do not attach `ToolCallingAdvisor`.

**Rationale**: A direct model response exposes requested tool calls without entering Spring AI's tool-execution loop. Strict schemas constrain output shape, while the throwing callbacks make accidental execution fail closed.

**Alternatives rejected**: `ChatClient` with tool advisors risks automatic execution; parsing free-form JSON gives weaker schema guarantees; a conversational agent adds state and autonomy that the review workflow does not need.

## Fixed typed proposal union

**Decision**: Normalize every recognized tool call into an `actionType` plus a typed payload and validation state. Unknown or malformed calls fail the analysis request; `no_action` produces an empty successful batch.

**Rationale**: A discriminated union gives backend and frontend exhaustive handling and permits domain-specific editors without retaining provider-specific argument shapes.

**Alternatives rejected**: A generic map payload would make validation, API compatibility, and safe execution harder to prove.

## Privacy-bounded context and telemetry marker

**Decision**: Build context from one authorised family only, include the minimum metadata listed in the specification, and tag the Spring AI prompt metadata with `coparent.assistant.suppress-content=true`. The observation filter checks that marker before rendering request or response content.

**Rationale**: Data minimisation is enforceable in code and the marker prevents global Langfuse capture settings from overriding this feature's stronger privacy rule.

**Alternatives rejected**: Globally disabling content capture would degrade unrelated observability; relying on prompt wording would not enforce data boundaries.

## Retry-safe action state machine without transactions

**Decision**: Preallocate result identifiers for creates and messages, atomically claim `PENDING`/retryable `FAILED` actions as `APPLYING`, write an internal assistant action marker on each domain effect, then finalize the action as `APPLIED`. A stale `APPLYING` reconciliation checks the marker/result identifier and either finalizes or safely replays.

**Rationale**: The state machine survives crashes between mutation and proposal finalization and makes retries deterministic on the existing single-document Mongo operations.

**Alternatives rejected**: Mongo transactions are not required by the existing architecture and do not cover provider/network boundaries; client idempotency alone cannot recover server crash windows.

## Seven-day ephemeral aggregate

**Decision**: Store one batch document containing its actions in `assistantproposalbatches`, indexed by family/subject/created time and by expiry. Use a TTL index on `expiresAt` and omit the collection from backup allowlists while recreating it after restore.

**Rationale**: Actions are always loaded and authorised through their owning batch, so one aggregate supports atomic claims and revision checks with a simple ownership query.

**Alternatives rejected**: Separate action documents increase coordination and ownership-query complexity without a demonstrated scale need.

## Existing visual language with a proposal ledger

**Decision**: Use the existing CoParent teal/rose palette and typography. The signature interaction is an ordered "capture → review → applied" ledger: compact type labels, a visible status rail, and expandable type-specific cards in a Vaul drawer.

**Rationale**: It feels native to CoParent while making the human decision boundary more prominent than AI branding.

**Alternatives rejected**: A chat UI implies conversational authority and hides batch state; a generic sparkle/gradient AI surface conflicts with the product's practical family-planning tone.

## Model capability

**Decision**: Default the independent assistant model property to `gpt-5.4-nano`.

**Rationale**: The model supports text and image input, Chat Completions, function calling, structured outputs, and reasoning effort `none`, matching the constrained extraction workload.

**Alternatives rejected**: Sharing the general chat model setting couples unrelated behavior and rollout; a larger model is unnecessary as the default for bounded extraction.
