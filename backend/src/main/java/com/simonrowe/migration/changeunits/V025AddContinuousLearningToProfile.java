package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.util.Date;
import org.bson.Document;

@ChangeUnit(id = "add-continuous-learning-to-profile", order = "025", author = "simonrowe")
public class V025AddContinuousLearningToProfile {

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    Query profileQuery = new Query();
    Document profile = mongoTemplate.findOne(profileQuery, Document.class, "profiles");
    if (profile != null) {
      String desc = profile.getString("description");
      if (desc != null
          && !desc.contains("what is relevant today might not be so relevant tomorrow")) {
        desc = desc + "\n\nI never stand still. I'm constantly upgrading my skills "
            + "because what is relevant today might not be so relevant tomorrow.";
        mongoTemplate.updateFirst(
            profileQuery,
            new Update().set("description", desc).set("updatedAt", new Date()),
            "profiles"
        );
      }
    }
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
  }
}
