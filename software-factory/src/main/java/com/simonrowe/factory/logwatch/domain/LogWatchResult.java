package com.simonrowe.factory.logwatch.domain;

import java.util.List;

/**
 * How a scan ended.
 *
 * @param status the terminal status
 * @param sourceHealth what was concluded about the log source
 * @param linesRead how many lines were read
 * @param containersSeen how many distinct containers produced lines
 * @param signaturesFound distinct problems surviving the minimum-occurrence filter
 * @param signaturesDropped how many were lost to the per-run cap
 * @param truncated whether the read hit its line budget and so examined only part of the window
 * @param issueUrls the Linear issues filed or commented on; empty on a dry run
 * @param detail free-text diagnostics
 */
public record LogWatchResult(
    LogWatchStatus status,
    SourceHealth sourceHealth,
    int linesRead,
    int containersSeen,
    int signaturesFound,
    int signaturesDropped,
    boolean truncated,
    List<String> issueUrls,
    String detail) {

  public LogWatchResult {
    issueUrls = issueUrls == null ? List.of() : List.copyOf(issueUrls);
  }
}
