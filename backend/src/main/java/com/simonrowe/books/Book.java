package com.simonrowe.books;

import com.simonrowe.common.Image;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A book on the Reading list, managed in the CMS.
 *
 * <p>{@code slug} is unique, enforced by the {@code idx_books_slug} index that
 * {@code V062CreateBooks} creates — {@code auto-index-creation} is off, so an {@code @Indexed}
 * here would be decorative.
 *
 * <p>{@code summary} is the paragraph on the card; {@code description} (markdown) and
 * {@code note} (why it is on the list) appear only in the drawer. {@code category} is free text,
 * and the page groups books under each category in the order the first of them appears.
 * {@code publishedYear} is the year the work was first published, not the year of the edition
 * {@code purchaseUrl} points at, which is the one fact true of every edition.
 */
@Document(collection = Book.COLLECTION)
public record Book(
    @Id String id,
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
    int displayOrder,
    boolean published,
    Instant createdAt,
    Instant updatedAt
) {

  public static final String COLLECTION = "books";

  public Book {
    authors = authors == null ? List.of() : List.copyOf(authors);
  }

  /** Where the book is on the site: the Reading list, with its drawer open. */
  public String siteUrl() {
    return "/reading?book=" + URLEncoder.encode(slug, StandardCharsets.UTF_8);
  }

  /** A copy at another position in the list, so reordering cannot drop a field it never names. */
  public Book withDisplayOrder(final int order, final Instant now) {
    return new Book(id, slug, title, subtitle, authors, publishedYear, category, summary,
        description, note, coverImage, purchaseUrl, sourceLabel, sourceUrl, order, published,
        createdAt, now);
  }
}
