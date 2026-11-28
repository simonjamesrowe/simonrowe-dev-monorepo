package com.simonrowe.coparent.model;

/**
 * Where a document came from.
 *
 * @param kind how it was added
 * @param schoolDocumentId the Term Time {@code SchoolDocument} it came from, for the two school
 *     kinds; null otherwise
 */
public record DocumentSource(Kind kind, String schoolDocumentId) {

  /** How a document was added. */
  public enum Kind {
    /** A parent uploaded it on the documents page. */
    UPLOAD,
    /** Quick add read it from a photo and the parent approved the card. */
    QUICK_ADD,
    /** Kept from a school letter offered under "From school". */
    SCHOOL_LETTER,
    /** Uploaded to fulfil a "report is ready" prompt from the school's reporting system. */
    SCHOOL_REPORT
  }

  public DocumentSource {
    kind = kind == null ? Kind.UPLOAD : kind;
  }

  /** A document a parent uploaded themselves. */
  public static DocumentSource upload() {
    return new DocumentSource(Kind.UPLOAD, null);
  }
}
