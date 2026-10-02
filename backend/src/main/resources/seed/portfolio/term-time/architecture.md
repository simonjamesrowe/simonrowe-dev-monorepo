## Under the hood

Term Time runs inside this site's Spring Boot backend, on the same Raspberry Pi as everything
else. It has its own Elasticsearch index and its own chat client, and its frontend is a second
Vite entry point in the same project, served at `term-time.simonrowe.dev`.

![Ingest on the left, the request path on the right. The scheduler drives the crawler, calendar client and Gmail sync; extraction writes to MongoDB and Elasticsearch. A question arrives over STOMP, passes a topic guardrail, and the chat service answers through its tools.]({{media:architecture.svg}})

- **Backend:** Java 25, Spring Boot 4.1 and Spring AI 2.0
- **Extraction:** Embabel, turning newsletters, letters and PDFs into dated events
- **Storage:** MongoDB 8 for documents and events, Elasticsearch 9 for the vectors
- **Chat:** answers stream over STOMP on a WebSocket
- **Frontend:** React 19 and Vite, reusing this site's chat components
- **Observability:** a Langfuse trace for every turn

## Dates are a query, not a similarity search

"When is half term?" has one right answer, so Term Time looks it up. A vector search for "this
week" returns whatever text sounds most like "this week", which is rarely this week.

The model chooses between tools: term dates, INSET days, events between two dates, clubs, what
the school sent in a date range, and a search for everything else. The events tool also fetches
the letters that describe those events, searching on the event titles it just found.

```java
@Tool(description = """
    Get school events between two dates, together with the newsletters, letters and PDFs that \
    describe them. Use for 'what is on this week', 'what is happening next month' and similar.""")
public String getEventsBetween(
    @ToolParam(description = "Start date, ISO format yyyy-MM-dd") final String from,
    @ToolParam(description = "End date, ISO format yyyy-MM-dd") final String to) {
  // ... parse the two dates ...
  return tracked(EVENTS_LABEL, () -> {
    final List<SchoolEvent> events = queries.eventsBetween(start, end, yearGroups, audience);
    final String dated = render(events, "Nothing is recorded for that period.");
    final String context = supportingProse(events, start, end);
    return context.isEmpty() ? dated : """
        %s

        Supporting detail from school communications:

        %s""".formatted(dated, context);
  });
}
```

## Restricted until someone says otherwise

A document with no visibility recorded is restricted, and that is decided in the record's
constructor rather than by every caller remembering to check. The classifier can only attach a
proposal. Approving a document is the only way to make it public.

```java
public SchoolDocument {
  visibility = visibility == null ? Visibility.RESTRICTED : visibility;
  yearGroups = yearGroups == null ? List.of() : List.copyOf(yearGroups);
}

/** Returns a copy carrying a classifier's proposal. Deliberately cannot alter visibility. */
public SchoolDocument withProposal(final Visibility proposed, final String reason) {
  // ...
}
```

Approving a document also re-embeds its chunks. The visibility is stored in Elasticsearch as
well as MongoDB, and the retrieval filter reads Elasticsearch, so an approval that only updated
MongoDB would leave the document public in one store and restricted in the other.

## Scheduled in the backend, not Temporal

This site's software factory runs on Temporal, which retries failed work better than a scheduled
method does. Term Time stays on Spring's scheduler because a Temporal worker would run in the
image the deployer shares, and the deployer holds the Docker socket. The school's Gmail
credential should never be in that image.

```java
// fixedDelay, not fixedRate: a website crawl honouring a ten-second delay per page takes over
// half an hour, and fixedRate would start the next pass while the previous one was still going.
@Scheduled(initialDelayString = STARTUP_DELAY_GMAIL, fixedDelayString = GMAIL_INTERVAL)
public void ingestMail() {
  gmailIngestService.sync();
}

@Scheduled(initialDelayString = STARTUP_DELAY_WEBSITE, fixedDelayString = WEBSITE_INTERVAL)
public void ingestWebsite() {
  ingestService.refreshStaffDirectory();
  ingestService.ingestWebsite();
}
```

## A second vector store without losing the first

Spring AI's Elasticsearch auto-configuration only creates its store when no other `VectorStore`
bean exists. Publishing a second one for the school would silently remove the main site's. So the
school store is held inside a wrapper that is not a `VectorStore`, and because Spring never sees
the store inside it, its schema set-up has to be called by hand.

```java
final ElasticsearchVectorStore store = ElasticsearchVectorStore
    .builder(client, model)
    .options(options)
    .initializeSchema(true)
    .build();

// initializeSchema(true) only takes effect from afterPropertiesSet(), which Spring calls on the
// wrapper and never on the store inside it. Without this the index is never created.
store.afterPropertiesSet();

return new SchoolVectorStore(store, indexName);
```
