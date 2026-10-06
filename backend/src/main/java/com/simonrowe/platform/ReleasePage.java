package com.simonrowe.platform;

import java.util.List;
import java.util.Map;

/**
 * One page of the changelog.
 *
 * @param items the releases on this page, newest first
 * @param page the zero-based page number
 * @param size the page size actually applied, after clamping
 * @param totalItems how many releases match the type and search filters
 * @param totalPages how many pages those matches fill
 * @param totalReleases how many releases are stored, ignoring every filter
 * @param typeCounts releases per conventional-commit type, ignoring every filter. Counts label
 *     the type pills, the news page's precedent: numbers that move while you type are harder
 *     to choose from
 */
public record ReleasePage(
    List<ReleaseResponse> items,
    int page,
    int size,
    long totalItems,
    int totalPages,
    long totalReleases,
    Map<String, Long> typeCounts) {
}
