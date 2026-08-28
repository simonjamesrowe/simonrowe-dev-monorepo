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

@ChangeUnit(id = "rewrite-experience-descriptions", order = "026", author = "simonrowe")
public class V026RewriteExperienceDescriptions {

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    // 1. Pivotal
    String pivotalLd = "## Role Overview\n\n"
        + "Applications focused platform architect, aiding clients to become successful by building cloud-native apps utilizing the Spring ecosystem and platforms like Cloud Foundry and Kubernetes.\n\n"
        + "## Responsibilities & Achievements\n\n"
        + "- Delivering Cloud-Native Developer and other workshops to a number of clients which contained topics including:\n"
        + "  - Introduction to Spring Boot\n"
        + "  - Reactive programming with Spring Boot and Project Reactor\n"
        + "  - Producer driven contracts with Spring Cloud Contract\n"
        + "  - Cloud Foundry and BOSH\n"
        + "  - The mechanics behind `cf push` and the service broker.\n"
        + "- Providing technical pre-sales support and architectural guidance.\n"
        + "- Troubleshooting complex issues on Cloud Foundry and assisting development teams in adopting modern cloud-native practices.\n";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("Pivotal")), new Update().set("longDescription", pivotalLd), "jobs");

    // 2. Universal Music Publishing
    String umpgLd = "## Senior Director, Java Development (Feb 2015 - Aug 2018)\n\n"
        + "**Responsibilities:**\n"
        + "- Product Development and Delivery, Solutions Architecture, Cloud Identity and Security.\n"
        + "- Oversight and mentoring multiple cross-functional scrum teams.\n"
        + "- **Technologies:** Java 8, Spring Boot, AWS, Angular, Docker, Cucumber, Solr, ElasticSearch, MySQL, MongoDB, DynamoDB.\n\n"
        + "**Achievements:**\n"
        + "- Re-architecture of all internal and external facing applications to a fault-tolerant solution powered by AWS. This resulted in a reduction of operating costs (by 30%), whilst delivering high availability.\n"
        + "- Improvement of automated test strategy – introduced Cucumber and Selenium into our CI pipeline, resulting in the team being able to release to production far more frequently with less risk.\n"
        + "- Produced UMPG’s first fully 'server-less' application utilising AWS infrastructure (AWS Lambda, ECS Fargate, ELB, CloudFormation). Using CloudFormation stacks containing serverless resources allowed our teams to focus more time on product development rather than operations.\n\n"
        + "## Java Team Leader (Jul 2011 - Feb 2015)\n\n"
        + "**Responsibilities:**\n"
        + "- Overseeing a small agile team containing on-site and 3rd party members utilising Scrum methodology.\n"
        + "- Architectural design of business-critical enterprise applications and liaising with 3rd party infrastructure vendors to ensure resilient, highly-available solutions.\n"
        + "- Collaboration with product management and QA teams to ensure business requirements are delivered.\n"
        + "- Implementation and evolution of coding/architectural standards.\n"
        + "- **Technologies:** Java, JEE, Spring, Hibernate, JPA, Solr, JSP, HTML, Javascript, JQuery, CSS, Mockito, DbUnit, Maven, Ant, Jenkins, Cucumber, Capybara.\n\n"
        + "**Achievements:**\n"
        + "- Delivery of Royalty Window Real Time Balances project. Evaluated delivery options and led the team to deliver a solution that exceeded expectations and reduced operational costs by moving to an open-source technology stack.\n"
        + "- Delivery of UMPG Works, providing an advanced search engine and enquiry system over all works within the UMPG catalogue.\n"
        + "- Design and delivery of the Trend Analysis application, allowing senior management real-time detailed trend-related statistics.\n";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("Universal Music Publishing")), new Update().set("longDescription", umpgLd), "jobs");

    // 3. Workcover Queensland
    String wcqLd = "## Role Overview\n\n"
        + "**Responsibilities:**\n"
        + "- Analysis, Design, Development, Testing & Documentation of enhancements to internal and external facing web applications within an agile environment.\n"
        + "- Mentoring of less experienced developers.\n"
        + "- **Technologies:** JEE, JSP, Spring, Ibatis, Tiles, HTML, JQuery, Oracle, Ruby, Cucumber, Watir, PowerMock, EasyMock, TeamCity, Weblogic, Jasper Reports.\n\n"
        + "**Achievements:**\n"
        + "- Successful delivery of the nine-month Premium project – resulting in approximately 90% of clients being able to manage their policies online. This highly available system delivered simplicity and convenience to clients and significantly reduced operating costs within customer services.\n"
        + "- **CASA** – Migration of internal systems to a JEE web application, resulting in a substantial reduction to organizational operating costs by decommissioning obsolete technologies while enhancing user experience.\n"
        + "- **CLMO** - Technical lead on a three-month project that delivered a highly configurable system allowing business analysts to accurately and efficiently analyse and monitor the effects of legislative changes on damages claims costs.\n";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("Workcover Queensland")), new Update().set("longDescription", wcqLd), "jobs");

    // 4. Macquarie Group
    String mqLd = "## Role Overview\n\n"
        + "**Responsibilities:**\n"
        + "- Design, Build, Test & Document new software solutions across the Risk Management Group.\n"
        + "- Maintain and enhance existing systems while mentoring junior developers.\n"
        + "- **Technologies:** JEE, Java, JSP, HTML, JQuery, Dojo, Spring, Hibernate, Sybase, HSQLDB, SQL Server, Tiles, JUnit, Selenium, PHP.\n\n"
        + "**Achievements:**\n"
        + "- **MECS (Macquarie Economic Capital System)** – JEE application allowing for calculation and monitoring of required held capital. Replaced manual labor, mitigating operational risk while significantly improving efficiency of risk analysts.\n"
        + "- **PARS (Position Aggregation and Reporting System)** – JEE application calculating aggregate positions and alerting control room staff of legislative actions required due to position changes, delivering crucial performance improvements.\n"
        + "- **MBI (Market Data and Business Intelligence Portal)** – Batch processing and aggregation of market data from multiple sources into a web-based search portal and web services.\n"
        + "- Introduced many improvements to the ITG RMG team, including the adoption of test-driven development, creation of a common JSP 2.0 tag library for consistent use across the entire suite of applications, and introduction of Spring Batch.\n"
        + "- Creation of a new timesheet and billing system written in PHP for ITG employees, simplifying reporting of project budgets and costs.\n";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("Macquarie Group")), new Update().set("longDescription", mqLd), "jobs");

    // 5. SAS
    String sasLd = "## Role Overview\n\n"
        + "**Responsibilities:**\n"
        + "- Design, Development, Testing and Documentation of the Synapse internal business software.\n"
        + "- 3rd Level Support.\n"
        + "- **Technologies:** SAS, Java, JSP, JEE, Spring, Struts, Web Services, XFire.\n\n"
        + "**Achievements:**\n"
        + "- Developed and Maintained the Synapse internal web application, handling Education, Training, Contracts, Consulting, Sales, Marketing, and Reporting. Converted Synapse from a SAS AF-based thick client to a JEE application under an agile (Scrum) methodology, resulting in quicker turnaround times for business changes.\n"
        + "- Major contributor to the internally developed Eclipse “Vertical Slice” plug-in, which generated code for various layers within the application's architecture, improving efficiency and letting developers focus on pure business logic.\n";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("SAS")), new Update().set("longDescription", sasLd), "jobs");

    // 6. University of Newcastle
    String uniLd = "## Bachelor of Computer Science\n\n"
        + "**Awards & Recognition:**\n"
        + "- **Dean's Merit List** for outstanding academic performance in both 2003 and 2004.\n"
        + "- **Dean's Achievement Award** (2002) Recognising Outstanding Academic Performance.\n"
        + "- **Object Oriented Technology Prize Winner** (2004), presented by Object Consulting.\n"
        + "- **Certificate of Merit** (Nextgen.net Prize) for excellence in Introduction to Web Engineering (Team Assignment).\n\n"
        + "**Academic Highlights:**\n"
        + "- Consistently achieved High Distinctions (HD) and Distinctions (D) across core computer science and software engineering subjects, including Object-Oriented Software Engineering, Computer Graphics, Software Distributed Environments, and Concurrent Programming.\n";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("University of Newcastle")), new Update().set("longDescription", uniLd), "jobs");
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
  }
}
