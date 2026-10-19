package com.simonrowe.books;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.simonrowe.common.Image;
import java.util.List;

/**
 * A book as the public site sees it: everything the card and the drawer show, in one response,
 * so opening the drawer (or a {@code ?book=} deep link) needs no second request. Absent optional
 * fields are omitted rather than sent as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicBook(
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
    int displayOrder
) {
}
