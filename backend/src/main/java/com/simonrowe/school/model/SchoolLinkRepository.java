package com.simonrowe.school.model;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for {@link SchoolLink}. */
public interface SchoolLinkRepository extends MongoRepository<SchoolLink, String> {

  /**
   * Links found in one document.
   *
   * @param sourceDocumentId the document
   * @return its links, whatever their status
   */
  List<SchoolLink> findBySourceDocumentId(String sourceDocumentId);

  /**
   * Links in a given state.
   *
   * @param status the state
   * @return matching links
   */
  List<SchoolLink> findByStatus(SchoolLink.Status status);

  /**
   * Links found in any of several documents, for the list view.
   *
   * @param sourceDocumentIds the documents on the current page
   * @return their links
   */
  List<SchoolLink> findBySourceDocumentIdIn(List<String> sourceDocumentIds);
}
