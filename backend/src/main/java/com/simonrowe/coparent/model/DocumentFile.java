package com.simonrowe.coparent.model;

import org.bson.types.ObjectId;

/**
 * One stored file of a document version: a page photo or a PDF. The bytes never live in Mongo;
 * they are at {@code <familyId>/<fileId>} in {@code CoparentDocumentStore}.
 *
 * @param fileId the file's name in the store
 * @param mimeType the type its bytes were proven to be on upload
 * @param sizeBytes its size
 */
public record DocumentFile(ObjectId fileId, String mimeType, long sizeBytes) {
}
