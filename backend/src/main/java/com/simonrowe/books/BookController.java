package com.simonrowe.books;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The public Reading list: published books only. Unauthenticated, like every public read. */
@RestController
@RequestMapping("/api/books")
public class BookController {

  private final BookService service;

  public BookController(final BookService service) {
    this.service = service;
  }

  @GetMapping
  public List<PublicBook> list() {
    return service.publicList();
  }
}
