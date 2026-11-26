package com.simonrowe.school.ingest;

/**
 * Published after each scheduled school sync (calendar, mailbox and publication judge, website,
 * newsletters), whether or not it found anything, so another module can read the school's events
 * again without the school module knowing who reads them.
 *
 * @param source which sync ran, for logs
 */
public record SchoolEventsRefreshed(String source) {
}
