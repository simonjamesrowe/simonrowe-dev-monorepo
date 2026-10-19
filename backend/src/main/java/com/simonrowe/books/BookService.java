package com.simonrowe.books;

import com.simonrowe.admin.FieldValidationException;
import com.simonrowe.admin.ValidationErrorResponse.FieldError;
import com.simonrowe.common.Image;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.media.MediaImageHydrator;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BookService {

  private final BookRepository repository;
  private final MediaImageHydrator imageHydrator;
  private final ContentChangePublisher changes;

  public BookService(
      final BookRepository repository,
      final MediaImageHydrator imageHydrator,
      final ContentChangePublisher changes
  ) {
    this.repository = repository;
    this.imageHydrator = imageHydrator;
    this.changes = changes;
  }

  /** Published books in display order, with each cover resolved to its library variants. */
  public List<PublicBook> publicList() {
    return repository.findByPublishedTrueOrderByDisplayOrderAscTitleAsc().stream()
        .map(this::toPublic)
        .toList();
  }

  public List<Book> adminList() {
    return repository.findAllByOrderByDisplayOrderAscTitleAsc();
  }

  public Book adminGet(final String id) {
    return repository.findById(id).orElseThrow(() -> notFound(id));
  }

  public Book create(final BookRequest request) {
    validate(request);
    int order = request.displayOrder() != null
        ? request.displayOrder()
        : repository.findAllByOrderByDisplayOrderAscTitleAsc().stream()
            .mapToInt(Book::displayOrder).max().orElse(-1) + 1;
    Instant now = Instant.now();
    Book saved = saveUnique(toDocument(null, request, order, now, now));
    // Search and the chat's context pick the change up from here; an unpublished book is
    // removed from both rather than indexed.
    changes.publishCreated(ContentType.BOOK, saved.id());
    return saved;
  }

  /** Replaces every editable field; the display order is kept unless the request sets one. */
  public Book update(final String id, final BookRequest request) {
    Book existing = adminGet(id);
    validate(request);
    int order = request.displayOrder() != null ? request.displayOrder() : existing.displayOrder();
    Book saved = saveUnique(toDocument(id, request, order, existing.createdAt(), Instant.now()));
    changes.publishUpdated(ContentType.BOOK, saved.id());
    return saved;
  }

  public void delete(final String id) {
    if (!repository.existsById(id)) {
      throw notFound(id);
    }
    repository.deleteById(id);
    changes.publishDeleted(ContentType.BOOK, id);
  }

  /** Reorders every book. The list must name each existing book exactly once. */
  public void reorder(final List<String> orderedIds) {
    Map<String, Book> byId = repository.findAll().stream()
        .collect(Collectors.toMap(Book::id, Function.identity()));
    if (orderedIds == null
        || orderedIds.size() != byId.size()
        || new HashSet<>(orderedIds).size() != orderedIds.size()
        || !byId.keySet().containsAll(orderedIds)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "orderedIds must list every book exactly once");
    }
    Instant now = Instant.now();
    for (int i = 0; i < orderedIds.size(); i++) {
      Book book = byId.get(orderedIds.get(i));
      if (book.displayOrder() != i) {
        repository.save(book.withDisplayOrder(i, now));
      }
    }
  }

  /**
   * Saves and lets the unique slug index decide, rather than checking first: a read-then-write
   * check is a race two concurrent saves can both pass.
   */
  private Book saveUnique(final Book book) {
    try {
      return repository.save(book);
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Another book already uses the slug " + book.slug());
    }
  }

  private PublicBook toPublic(final Book book) {
    return new PublicBook(
        book.slug(),
        book.title(),
        book.subtitle(),
        book.authors(),
        book.publishedYear(),
        book.category(),
        book.summary(),
        book.description(),
        book.note(),
        hydrate(book.coverImage()),
        book.purchaseUrl(),
        book.sourceLabel(),
        book.sourceUrl(),
        book.displayOrder());
  }

  private Image hydrate(final Image image) {
    if (image == null || image.url() == null) {
      return null;
    }
    // Kept even if the hydrator never returns null today: it is the media library's code,
    // and the NPE from a future change there would be on a public page.
    Image hydrated = imageHydrator.hydrate(image, "medium", "small", "large");
    return hydrated != null ? hydrated : image;
  }

  private static void validate(final BookRequest request) {
    List<FieldError> errors = BookValidator.validate(request);
    if (!errors.isEmpty()) {
      throw new FieldValidationException(errors);
    }
  }

  private static Book toDocument(
      final String id,
      final BookRequest request,
      final int order,
      final Instant createdAt,
      final Instant updatedAt
  ) {
    Image cover = request.coverImage() == null || isBlank(request.coverImage().url())
        ? null
        : new Image(request.coverImage().url().strip(), null, null, null, null, null);
    return new Book(
        id,
        request.slug(),
        request.title().strip(),
        clean(request.subtitle()),
        request.authors().stream().map(String::strip).toList(),
        request.publishedYear(),
        request.category().strip(),
        request.summary().strip(),
        // Markdown, where leading indentation means something, so it is kept verbatim.
        isBlank(request.description()) ? null : request.description(),
        isBlank(request.note()) ? null : request.note(),
        cover,
        clean(request.purchaseUrl()),
        clean(request.sourceLabel()),
        clean(request.sourceUrl()),
        order,
        request.published(),
        createdAt,
        updatedAt);
  }

  private static String clean(final String value) {
    return isBlank(value) ? null : value.strip();
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }

  private static ResponseStatusException notFound(final String id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found: " + id);
  }
}
