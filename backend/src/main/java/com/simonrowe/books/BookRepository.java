package com.simonrowe.books;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BookRepository extends MongoRepository<Book, String> {

  List<Book> findByPublishedTrueOrderByDisplayOrderAscTitleAsc();

  List<Book> findAllByOrderByDisplayOrderAscTitleAsc();
}
