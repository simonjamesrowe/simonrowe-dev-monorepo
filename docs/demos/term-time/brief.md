# Term Time demo brief

## Product
Term Time, a school assistant for Kilmorie Primary at https://term-time.simonrowe.dev. Chat scenes
run read-only on production. Admin scenes run on the local stack with the latest production backup
restored, because saving a note has real effects.

## Audience
Both: parents first (how to use it), then people interested in how it is built.

## Aim
Term Time answers questions about school life from what the school actually published.

## Key messages
1. Answers cite their source (scenes `source`, `year-six-answer`).
2. Answers are year-group aware (scenes `year-six`, `year-six-answer`).

## The moment
Ticking Year 6 and getting the Year 6 residential with a link to its letter (`year-six-answer`).
Proposed; awaiting Simon's confirmation.

## Diagrams
`sources`, walked through as sources, then ingest, then stores and answers.

## Out of scope
Clubs, INSET days, the events admin and approving anything.

## Constraints
About two minutes. Admin needs a signed-in session (`auth.json`). Anything confidential on the
approvals screen is blurred. LLM answers vary between takes, so scenes wait for the answer.
Slug: term-time.

## Outro
That was Term Time, a school assistant that answers from what the school actually published.
Every answer carries its source, so parents can check it in one tap. (Proposed; awaiting approval.)
