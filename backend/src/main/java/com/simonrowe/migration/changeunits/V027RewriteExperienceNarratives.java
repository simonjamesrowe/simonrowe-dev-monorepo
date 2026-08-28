package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.util.Date;

@ChangeUnit(id = "rewrite-experience-narratives", order = "027", author = "simonrowe")
public class V027RewriteExperienceNarratives {

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    
    // 1. Pivotal
    String pivotalLd = "As a Senior Platform Architect at Pivotal, I worked directly with enterprise clients to help them navigate their cloud-native transformations. A significant part of my role involved leading hands-on workshops for development teams, teaching them the core concepts of Spring Boot, reactive programming with Project Reactor, and producer-driven contract testing.\n\n"
        + "Beyond the classroom, I acted as a trusted technical advisor, providing pre-sales support and diving deep into complex Cloud Foundry and Kubernetes deployments to troubleshoot issues and ensure smooth operations. This role allowed me to blend technical problem-solving with high-level architectural guidance, empowering organisations to fundamentally change how they build and run software.";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("Pivotal")), new Update().set("longDescription", pivotalLd), "jobs");

    // 2. Universal Music Publishing
    String umpgLd = "During my tenure at UMPG, I led a major technological shift, overseeing multiple cross-functional Agile teams to modernize business-critical systems. As a Senior Director and Java Team Leader, I spearheaded the re-architecture of both internal and external platforms, migrating them to a highly resilient, fault-tolerant AWS infrastructure.\n\n"
        + "This wasn't just a lift-and-shift; we embraced cloud-native patterns and built UMPG's first fully serverless application using AWS Lambda and Fargate, reducing operational overhead and cutting running costs by 30%. I also championed a rigorous approach to software quality, introducing automated CI/CD pipelines with Cucumber and Selenium to enable faster, safer releases.\n\n"
        + "My teams successfully delivered several flagship products, including the UMPG Works copyright search engine, a real-time Royalty Window balances platform that modernized how artist royalties were tracked, and UMPG Sync for global sync licensing.";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("Universal Music Publishing")), new Update().set("longDescription", umpgLd), "jobs");

    // 3. Workcover Queensland
    String wcqLd = "I joined Workcover Queensland as a Senior Applications Developer during a critical modernization phase. My primary focus was the successful delivery of the 'Premium' project—a highly available, customer-facing portal that allowed 90% of policyholders to manage their workers' compensation insurance online. This initiative dramatically improved the user experience and delivered significant operational savings to the customer services division.\n\n"
        + "I was also heavily involved in the CASA project, where we migrated legacy internal systems to a modern JEE web architecture, completely decommissioning outdated technologies in the process. Working across the full stack in an Agile environment, I took technical lead on the CLMO legislative analysis tool, whilst continuously mentoring junior team members and driving the adoption of robust testing frameworks like Cucumber and Watir.";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("Workcover Queensland")), new Update().set("longDescription", wcqLd), "jobs");

    // 4. Macquarie Group
    String mqLd = "At Macquarie Group, I worked as an Analyst/Programmer within the Risk Management Group, focusing on designing and building mission-critical financial software. I was instrumental in transitioning the division's monolithic, legacy applications into a modern, Spring-driven architecture.\n\n"
        + "Among the high-impact projects I delivered was the Macquarie Economic Capital System (MECS), which automated the calculation of required held capital, and the Position Aggregation and Reporting System (PARS), which monitored aggregate trading positions and generated real-time legislative alerts for the control room. I also built a unified JSP 2.0 tag library that was adopted as a standard across the entire suite of applications, ensuring UI consistency.\n\n"
        + "By introducing test-driven development and frameworks like Spring Batch, I helped mature the team's engineering culture and overall operational efficiency.";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("Macquarie Group")), new Update().set("longDescription", mqLd), "jobs");

    // 5. SAS
    String sasLd = "At SAS, my core responsibility was the design and development of 'Synapse', an expansive internal business management platform handling everything from consulting and sales to education and contracts. I led the technical effort to convert Synapse from an aging SAS AF-based thick-client application into a responsive, JEE-based web architecture using Spring and Struts.\n\n"
        + "In addition to delivering product features, I focused heavily on developer productivity. I was a major contributor to an internal Eclipse 'Vertical Slice' plug-in that scaffolded boilerplate code across architectural layers, drastically reducing repetitive work and allowing the team to focus purely on implementing complex business logic.";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("SAS")), new Update().set("longDescription", sasLd), "jobs");

    // 6. University of Newcastle
    String uniLd = "I graduated with a Bachelor of Computer Science from the University of Newcastle, achieving a highly competitive weighted average mark (WAM) of 81.24. My academic performance consistently placed me on the Dean's Merit List (2003 and 2004), a distinction reserved for students demonstrating exceptional academic achievement at the very top of their cohort. I was also honoured with the Dean's Achievement Award for outstanding performance in 2002.\n\n"
        + "Beyond individual grades, I excelled in collaborative software engineering challenges. My teams were awarded the Object Oriented Technology Prize (sponsored by Object Consulting) and the NextGen.net Certificate of Merit for our work in web engineering. In both instances, we took first place across the entire cohort for building fully working software solutions and successfully pitching them to industry sponsors.";
    mongoTemplate.updateFirst(new Query(Criteria.where("company").is("University of Newcastle")), new Update().set("longDescription", uniLd), "jobs");
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
  }
}
