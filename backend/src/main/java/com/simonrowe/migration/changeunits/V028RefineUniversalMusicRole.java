package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ChangeUnit(id = "refine-universal-music-role", order = "028", author = "simonrowe")
public class V028RefineUniversalMusicRole {

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    String umpgLd = "As a Senior Director at UMPG, I spearheaded a major technological shift, overseeing multiple cross-functional Agile teams to modernize business-critical systems. I led the re-architecture of both internal and external platforms, migrating them to a highly resilient, fault-tolerant AWS infrastructure.\n\n"
        + "This wasn't just a lift-and-shift; we embraced cloud-native patterns and built UMPG's first fully serverless application using AWS Lambda and Fargate, reducing operational overhead and cutting running costs by 30%. I also championed a rigorous approach to software quality, introducing automated CI/CD pipelines with Cucumber and Selenium to enable faster, safer releases.\n\n"
        + "My teams successfully delivered several flagship products, including the UMPG Works copyright search engine, a real-time Royalty Window balances platform that modernized how artist royalties were tracked, and UMPG Sync for global sync licensing.";
    
    // Also updating the title just to be 'Senior Director' to completely unify the roles
    mongoTemplate.updateFirst(
        new Query(Criteria.where("company").is("Universal Music Publishing")), 
        new Update()
            .set("longDescription", umpgLd)
            .set("title", "Senior Director"), 
        "jobs"
    );
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
  }
}
