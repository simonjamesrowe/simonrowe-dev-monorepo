package com.simonrowe.books;

import com.simonrowe.common.Image;
import java.util.List;

/**
 * Every editable field of a book, as the editor sends it. {@code displayOrder} may be null on
 * create, which appends the book to the end; reordering goes through {@code /reorder}.
 */
public record BookRequest(
    String slug,
    String title,
    String subtitle,
    List<String> authors,
    Integer publishedYear,
    String category,
    String summary,
    String description,
    String note,
    Image coverImage,
    String purchaseUrl,
    String sourceLabel,
    String sourceUrl,
    Integer displayOrder,
    boolean published
) {
}
