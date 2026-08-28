package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import org.bson.Document;

@ChangeUnit(id = "enrich-experience-and-profile", order = "023", author = "simonrowe")
public class V023EnrichExperienceAndProfile {

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    Query profileQuery = new Query();
    Document profile = mongoTemplate.findOne(profileQuery, Document.class, "profiles");
    if (profile != null) {
      String desc = profile.getString("description");
      if (desc != null && !desc.contains("Using multiple agents, software factories")) {
        desc = desc + "\n\nRecently, I've been heavily focused on exploring the capabilities of "
            + "multiple autonomous agents and building software factories to revolutionize how we "
            + "approach complex development tasks.";
        mongoTemplate.updateFirst(
            profileQuery,
            new Update().set("description", desc).set("updatedAt", new Date()),
            "profiles"
        );
      }
    }

    Map<String, String> shortDescUpdates = new HashMap<>();
    shortDescUpdates.put("Global", "Leading the engineering function for Commercial Trading "
        + "across three product pillars. Passionate about AI-native transformation, platform "
        + "modernization, and mentoring a talented team of 30+ engineers.");
    shortDescUpdates.put("Y-Tree", "Lead Developer for an exciting fin-tech platform, building "
        + "event-driven and RESTful microservices while driving cloud-native transformation.");
    shortDescUpdates.put("Upp Technologies", "Hands-on engineering lead for an agile "
        + "cross-functional team, delivering high-quality software solutions.");
    shortDescUpdates.put("Pivotal", "Technical pre-sales and Platform Architect. Ran Cloud "
        + "Native Developer workshops for Spring, Steeltoe, and .NET, and troubleshooted "
        + "complex Cloud Foundry issues. This role truly elevated my cloud-native and Spring "
        + "Boot skills to an expert level.");
    shortDescUpdates.put("Macquarie Group", "Brought new technology to the Risk Management "
        + "Group by transitioning monolithic WebSphere apps to Tomcat with Spring. Built the "
        + "new JSP tag library for PARS and credit risk platforms, and modernized the frontend "
        + "with jQuery.");
    shortDescUpdates.put("SAS", "Designed and developed the Synapse internal business "
        + "software. Championed the introduction of new technologies and modern frameworks "
        + "to the team.");
    shortDescUpdates.put("Civica", "Full-stack developer on the Authority application, "
        + "primarily building out the rates and assets modules. Gained broad experience "
        + "across Java, JEE, and various database platforms.");
    shortDescUpdates.put("Workcover Queensland", "Led a team building a self-service "
        + "portal for online workers' compensation insurance premiums. Migrated the Castor "
        + "application from Oracle Forms to a cloud-native modern Java Spring SPA architecture, "
        + "and introduced UI-based end-to-end testing with Watir and Cucumber.");
    shortDescUpdates.put("Universal Music Publishing", "Senior Director driving cloud "
        + "migration for business-critical applications (12-15 factor cloud-native apps). "
        + "Built systems like [UMPG Sync](https://www.umpg.com) for sync licensing and UMPG "
        + "Works for copyright. Rebuilt the royalty window with modern identity and revamped "
        + "end-to-end testing for [UMPG Window](https://www.umpgwindow.com).");

    for (Map.Entry<String, String> entry : shortDescUpdates.entrySet()) {
      Query q = new Query(Criteria.where("company").is(entry.getKey()));
      Update u = new Update().set("shortDescription", entry.getValue());
      
      if ("Global".equals(entry.getKey())) {
        Document newImage = new Document("url", "/images/global-logo.png")
                                 .append("name", "Global Logo")
                                 .append("mime", "image/png");
        u.set("companyImage", newImage);
      }
      
      mongoTemplate.updateFirst(q, u, "jobs");
    }
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
  }
}
