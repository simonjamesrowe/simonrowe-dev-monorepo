package com.simonrowe.aggregation;

import java.util.List;

/**
 * The body of {@code POST /api/admin/news/bulk} and {@code /api/admin/events/bulk}.
 *
 * @param ids the items to act on; at most {@link AggregatedContentAdminService#MAX_BULK_IDS}
 * @param action {@code hide}, {@code show} or {@code delete}
 */
public record BulkActionRequest(List<String> ids, String action) {
}
