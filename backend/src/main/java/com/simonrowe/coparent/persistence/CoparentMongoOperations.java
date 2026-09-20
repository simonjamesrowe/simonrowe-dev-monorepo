package com.simonrowe.coparent.persistence;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Unambiguous handle used when framework-managed code must target the CoParent database. */
@Component
public record CoparentMongoOperations(MongoTemplate template) {

  public CoparentMongoOperations(
      @Qualifier("coparentMongoTemplate") final MongoTemplate template) {
    this.template = template;
  }
}
