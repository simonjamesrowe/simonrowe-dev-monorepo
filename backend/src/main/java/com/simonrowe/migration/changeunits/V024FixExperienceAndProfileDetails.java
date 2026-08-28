package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.util.Date;
import org.bson.Document;

@ChangeUnit(id = "fix-experience-and-profile-details", order = "024", author = "simonrowe")
public class V024FixExperienceAndProfileDetails {

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    // 1. Profile Update
    Query profileQuery = new Query();
    Document profile = mongoTemplate.findOne(profileQuery, Document.class, "profiles");
    if (profile != null) {
      String desc = profile.getString("description");
      if (desc != null && !desc.contains("Software Factory")
          && !desc.contains("software factories")) {
        desc = desc + "\n\nRecently, I've been heavily focused on exploring the capabilities of "
            + "multiple autonomous agents and building **Software Factories** to revolutionize how "
            + "we "
            + "approach complex development tasks.";
        mongoTemplate.updateFirst(
            profileQuery,
            new Update().set("description", desc).set("updatedAt", new Date()),
            "profiles"
        );
      }
    }

    // 2. Global Logo & Short Description (Applying V023 changes again in case they
    // were lost, and fixing the Global logo)
    Query globalQuery = new Query(Criteria.where("company").is("Global"));
    Document newImage = new Document("url", "/images/global-logo.png")
                                 .append("name", "Global Logo")
                                 .append("mime", "image/png");
    mongoTemplate.updateFirst(globalQuery, new Update().set("companyImage", newImage), "jobs");

    // 3. Long Descriptions
    
    // Macquarie Group
    Query macquarieQuery = new Query(Criteria.where("company").is("Macquarie Group"));
    Document macquarie = mongoTemplate.findOne(macquarieQuery, Document.class, "jobs");
    if (macquarie != null) {
      String ld = macquarie.getString("longDescription");
      if (ld != null && !ld.contains("PARS")) {
        ld = "Brought new technology and practices to the Risk Management Group by transitioning "
            + "monolithic GE-type applications running on WebSphere to modern Tomcat applications "
            + "with Spring.\n\n"
             + "* Built the new JSP tag library for the Position Aggregation Reporting System "
             + "(PARS)\n"
             + "* Developed and maintained credit risk platforms\n"
             + "* Modernized the frontend architecture by introducing and implementing jQuery "
             + "across multiple projects\n\n"
             + ld;
        mongoTemplate.updateFirst(macquarieQuery, new Update().set("longDescription", ld), "jobs");
      }
    }

    // Civica
    Query civicaQuery = new Query(Criteria.where("company").is("Civica"));
    Document civica = mongoTemplate.findOne(civicaQuery, Document.class, "jobs");
    if (civica != null) {
      String ld = civica.getString("longDescription");
      if (ld != null && !ld.contains("[Authority]")) {
        ld = "Full-stack developer working primarily on the "
            + "[Authority](https://www.civica.com/en-au/product-pages/authority/) enterprise "
            + "application, a comprehensive local government software suite.\n\n"
             + "* Focused heavily on building and maintaining the Rates module for local councils\n"
             + "* Developed new functionality within the Assets module\n"
             + "* Gained broad, full-stack experience across Java, JEE, and numerous database "
             + "platforms\n\n"
             + ld;
        mongoTemplate.updateFirst(civicaQuery, new Update().set("longDescription", ld), "jobs");
      }
    }

    // Workcover Queensland
    Query wcQuery = new Query(Criteria.where("company").is("Workcover Queensland"));
    Document wc = mongoTemplate.findOne(wcQuery, Document.class, "jobs");
    if (wc != null) {
      String ld = wc.getString("longDescription");
      if (ld != null && !ld.contains("Watir")) {
        ld = "Led a team building a self-service portal enabling customers to manage and pay their "
            + "workers' compensation insurance premiums online.\n\n"
             + "* Migrated the legacy Castor application from Oracle Forms to a cloud-native, "
             + "modern Java Spring SPA (Single Page Application) architecture\n"
             + "* Pioneered the use of UI-based end-to-end testing by introducing Watir and "
             + "Cucumber into the testing lifecycle\n\n"
             + ld;
        mongoTemplate.updateFirst(wcQuery, new Update().set("longDescription", ld), "jobs");
      }
    }

    // Universal Music Publishing
    Query umpQuery = new Query(Criteria.where("company").is("Universal Music Publishing"));
    Document ump = mongoTemplate.findOne(umpQuery, Document.class, "jobs");
    if (ump != null) {
      String ld = ump.getString("longDescription");
      if (ld != null && !ld.contains("UMPG Sync")) {
        ld = "Senior Director driving cloud migration for business-critical applications, "
            + "transitioning systems to 12-15 factor cloud-native apps.\n\n"
             + "* Built and maintained [UMPG Sync](https://www.umpg.com) for sync licensing, "
             + "handling high-value music placement in film, TV, and advertising\n"
             + "* Developed UMPG Works, the core copyright management system\n"
             + "* Started work on [UMPG Window](https://www.umpgwindow.com), completely rebuilding "
             + "the royalty window and integrating a modern identity solution\n\n"
             + ld;
        mongoTemplate.updateFirst(umpQuery, new Update().set("longDescription", ld), "jobs");
      }
    }

    // Pivotal
    Query pivotalQuery = new Query(Criteria.where("company").is("Pivotal"));
    Document pivotal = mongoTemplate.findOne(pivotalQuery, Document.class, "jobs");
    if (pivotal != null) {
      String ld = pivotal.getString("longDescription");
      if (ld != null && !ld.contains("technical pre-sales")) {
        ld = "Technical pre-sales and Platform Architect aiding clients to become successful by "
            + "building cloud-native apps utilizing the Spring ecosystem.\n\n"
             + "* Troubleshooting complex issues on Cloud Foundry\n"
             + "* Delivered Cloud-Native Developer workshops for Spring, Steeltoe, and .NET\n\n"
             + ld;
        mongoTemplate.updateFirst(pivotalQuery, new Update().set("longDescription", ld), "jobs");
      }
    }
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
  }
}
