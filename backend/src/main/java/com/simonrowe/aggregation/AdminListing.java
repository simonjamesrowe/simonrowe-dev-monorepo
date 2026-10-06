package com.simonrowe.aggregation;

import java.util.List;
import org.springframework.data.domain.Sort;

/**
 * The filters and sort an admin listing of aggregated content was asked for.
 *
 * @param query free text; null or blank means no text filter
 * @param sources the source names to include; null or empty means every source
 * @param visibility which items to include by their {@code visible} flag
 * @param sort the order, already checked against the listing's allowlist by
 *     {@link AdminSort}
 */
public record AdminListing(
    String query,
    List<String> sources,
    VisibilityFilter visibility,
    Sort sort
) {
}
