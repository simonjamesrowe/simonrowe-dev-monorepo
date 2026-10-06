package com.simonrowe.aggregation;

import java.util.List;

/**
 * What a bulk request actually did.
 *
 * <p>{@code notFound} is reported rather than swallowed: an id that matched nothing has
 * usually been deleted by someone else already, and a count that silently came up short
 * would read as the action having partly failed.
 *
 * @param action the action applied, lower case
 * @param requested how many distinct ids were asked for
 * @param updated how many items were hidden, shown or deleted
 * @param notFound how many of the requested ids matched no item
 * @param notFoundIds those ids
 */
public record BulkActionResult(
    String action,
    int requested,
    long updated,
    int notFound,
    List<String> notFoundIds
) {
}
