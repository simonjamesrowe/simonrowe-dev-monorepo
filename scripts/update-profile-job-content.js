// Migration script: Update profile and Global job with enriched content
// Runs via mongosh inside the MongoDB container
// Idempotent — checks for marker before applying

const db = db.getSiblingDB('simonrowe');

// ============================================================================
// Idempotency check
// ============================================================================

const profile = db.profiles.findOne();
if (!profile) {
  print('ERROR: No profile document found. Run initial data migration first.');
  quit(1);
}

const MARKER_HEADLINE = 'PASSIONATE ABOUT AI-NATIVE DEVELOPMENT, CLOUD-NATIVE ARCHITECTURE, AND BUILDING HIGH-PERFORMING ENGINEERING TEAMS.';
if (profile.headline === MARKER_HEADLINE) {
  print('--- Content already updated (headline matches). Skipping. ---');
  quit();
}

print('=== Updating profile and Global job content ===');
print('');

// ============================================================================
// Phase 1: Update profile
// ============================================================================

print('--- Updating profile ---');

const newDescription = `I am driven to achieve real business value by incrementally delivering features through well-crafted software, underpinned by automated testing and continuous delivery. I care deeply about building high-performing engineering teams and creating cultures where technical excellence and continuous improvement are the norm.

Most recently, I've been leading engineering for commercial trading platforms at Europe's largest media and entertainment group — overseeing strategy, delivery, and the growth of 30+ engineers across multiple squads. A current focus has been driving **AI-native engineering practices** into everyday development workflows, from Claude Code adoption and MCP integration to structured evaluation frameworks for AI-assisted development.

I'm passionate about **cloud-native architecture**, **event-driven systems**, and **infrastructure-as-code** at scale. I believe the best engineering leaders stay close to the code, mentor generously, and build teams that own their quality, their architecture, and their growth.`;

db.profiles.updateOne(
  { _id: profile._id },
  {
    $set: {
      title: 'Engineering Leader',
      headline: MARKER_HEADLINE,
      description: newDescription,
      updatedAt: new Date()
    }
  }
);

print('Profile updated: title, headline, description');

// ============================================================================
// Phase 2: Add new skills to existing groups
// ============================================================================

print('');
print('--- Adding new skills to existing groups ---');

// Skills are stored in a separate 'skills' collection.
// skill_groups.skills holds an array of string IDs referencing them.
const newSkillsByGroup = {
  'Cloud': [
    {
      name: 'AWS Glue',
      rating: 7,
      description: 'Managed ETL service and Schema Registry for data integration, Avro schema management, and event sourcing serialization.',
      image: null
    },
    {
      name: 'AWS MSK',
      rating: 7,
      description: 'Amazon Managed Streaming for Apache Kafka — fully managed Kafka service for event-driven architectures and streaming data pipelines.',
      image: null
    },
    {
      name: 'AWS WAF',
      rating: 6,
      description: 'Web Application Firewall for protecting APIs and web applications with custom security rules including SSTI detection.',
      image: null
    }
  ],
  'Data Persistence / Search': [
    {
      name: 'Avro',
      rating: 7,
      description: 'Data serialization framework for compact binary encoding, schema evolution, and event sourcing message formats.',
      image: null
    }
  ]
};

let totalNewSkills = 0;

for (const [groupName, skills] of Object.entries(newSkillsByGroup)) {
  const group = db.skill_groups.findOne({ name: groupName });
  if (!group) {
    print('WARNING: Group "' + groupName + '" not found, skipping');
    continue;
  }

  // Resolve existing skill names from the skills collection
  const existingSkillIds = (group.skills || []).filter(s => typeof s === 'string');
  const existingSkillNames = [];
  for (const sid of existingSkillIds) {
    try {
      const s = db.skills.findOne({_id: ObjectId(sid)});
      if (s) existingSkillNames.push(s.name);
    } catch(e) {}
  }

  const maxOrder = existingSkillIds.length;
  let addedCount = 0;
  let orderOffset = 0;

  for (const skill of skills) {
    if (existingSkillNames.includes(skill.name)) {
      print('  Skill "' + skill.name + '" already exists in ' + groupName + ', skipping');
      continue;
    }

    // Also check if already in skills collection
    if (db.skills.findOne({name: skill.name})) {
      print('  Skill "' + skill.name + '" already in skills collection, skipping');
      continue;
    }

    orderOffset++;
    const skillId = new ObjectId();

    // Insert into skills collection
    db.skills.insertOne({
      _id: skillId,
      name: skill.name,
      rating: skill.rating,
      displayOrder: maxOrder + orderOffset,
      description: skill.description,
      image: skill.image || null
    });

    // Add string ID reference to the group
    db.skill_groups.updateOne(
      { _id: group._id },
      { $push: { skills: skillId.toString() } }
    );
    addedCount++;
    totalNewSkills++;
  }

  // Recalculate group rating from skills collection
  if (addedCount > 0) {
    const updatedGroup = db.skill_groups.findOne({ _id: group._id });
    const allSkillIds = (updatedGroup.skills || []).filter(s => typeof s === 'string');
    let totalRating = 0;
    let count = 0;
    for (const sid of allSkillIds) {
      try {
        const s = db.skills.findOne({_id: ObjectId(sid)});
        if (s && s.rating) { totalRating += s.rating; count++; }
      } catch(e) {}
    }
    if (count > 0) {
      const roundedRating = Math.round((totalRating / count) * 10) / 10;
      db.skill_groups.updateOne(
        { _id: group._id },
        { $set: { rating: roundedRating } }
      );
      print('  ' + groupName + ': added ' + addedCount + ' skills, new rating: ' + roundedRating);
    }
  } else {
    print('  ' + groupName + ': no new skills added');
  }
}

print('Total new skills added: ' + totalNewSkills);

// ============================================================================
// Phase 3: Update Global job content
// ============================================================================

print('');
print('--- Updating Global job ---');

const globalJob = db.jobs.findOne({ company: 'Global' });
if (!globalJob) {
  print('ERROR: Global job not found. Run seed-global-job first.');
  quit(1);
}

const newShortDescription = 'Head of Engineering for Commercial Trading at Global, leading AI-native transformation, platform modernization, and delivery across 30+ engineers and three product pillars.';

const newLongDescription = `## Role Overview

Head of Engineering for Commercial Trading at Global, Europe's largest media and entertainment group. Responsible for engineering strategy, team leadership, and technical delivery across Global's commercial trading platforms serving Heart, Capital, LBC, Classic FM, Radio X, Smooth, and Global Media & Entertainment. Leading a department of 30+ engineers across three pillars: Radio, Outdoor, and Shared & Self Service.

## AI-Native Engineering

- Pioneered Claude Code adoption across Commercial Trading, running structured POC trials and creating evaluation frameworks for AI-assisted development
- Designed MCP (Model Context Protocol) security patterns for commercial trading systems, implementing role-based permissions for AI tool integration
- Ran regular Claude Code Drop Sessions to share techniques, model upgrades, and best practices across engineering teams
- Created documentation and playbooks for AI-native engineering practices, establishing patterns for prompt engineering and AI-assisted code review

## Team Leadership & Management

- Managing multiple engineering squads comprising permanent developers and third-party contractors across the Radio, Outdoor, and Shared & Self Service pillars
- Recruited and onboarded engineers, growing the team with senior hires and establishing a stronger in-house engineering presence
- Developing and mentoring Tech Leads across each pillar, providing coaching on technical decisions, team management, and career growth
- Driving team engagement through recognition, inclusive decision-making, and creating opportunities for engineers to lead knowledge-sharing sessions and strategic initiatives
- Performance management including regular 1:1s, constructive feedback, and supporting professional development across the team
- Managed on-call rotation across engineering teams, ensuring production support coverage and incident response readiness

## Architecture & Technical Strategy

- Implementing federated OIDC authentication for external API calls, removing credential rotation concerns through service-to-service authentication using Kubernetes service account tokens
- Introducing feature flags via Spring Cloud for controlled rollouts without redeployment
- Designing event-driven integration architecture using Kafka, Avro serialisation, and AWS Glue Schema Registry for choreographed communication between order management, fulfilment, and planning systems
- Establishing shared technical standards covering service architecture (DDD, CQRS, pub-sub), deployment models, and access control (RBAC/ABAC)

## Security

- Coordinating security strategy in partnership with Information Security, ensuring alignment between engineering practices and organisational security policies
- Running threat vector exercises to identify and mitigate vulnerabilities across commercial trading services
- Implementing automated CVE detection and resolution pipelines to maintain up-to-date dependencies and reduce exposure windows

## Platform & Infrastructure

- Leading migration of workloads to gPillar v2 on AWS EKS, including the first services onto the new platform with coordinated infrastructure setup and networking
- Building shared Jenkins CI/CD pipelines and Terraform modules to standardise deployment across teams, including pipeline-based load testing
- Authoring and maintaining Terraform infrastructure-as-code across 30+ services spanning EKS, MSK, IAM, S3, and networking resources
- Creating MSK observability dashboards for Kafka cluster monitoring, consumer lag tracking, and throughput analysis
- Building the Deployment Helper tool (adopted and enhanced by other teams) to streamline deployment workflows and reduce minimum deployment times
- Enhancing the local development environment in collaboration with Tech Leads to improve developer experience across commercial trading engineering
- Managing AWS provider migrations and Terraform state restructuring to maintain infrastructure consistency

## Product Delivery

- Owning engineering delivery across three pillars — Radio, Outdoor, and Shared & Self Service — each with distinct product roadmaps, stakeholders, and technical challenges
- Coordinating cross-pillar initiatives including shared pricing services, order management enhancements, and platform-wide billing improvements
- Managing BAU release cycles across multiple squads, including risk assessment, rollback planning, and deployment sequencing
- Establishing engineering structures enabling in-house and external developer collaboration, growing products from concept through to production

## Engineering Practices

- Championing a shift-left testing approach with integration testing using Playwright, JUnit, and RestAssured, building a culture where quality is every developer's responsibility
- Implementing quality gates across all repositories: JaCoCo code coverage thresholds, Checkstyle enforcement, and OpenAPI contract-first development with spec validation
- Running knowledge-sharing sessions and ad-hoc group coaching on Java, unit testing, Spring Boot, Kubernetes, Helm, and CI/CD practices
- Standardising service architecture across teams with documented principles covering event-driven design, clean coding, DDD, and MVC patterns
- Building shared Kafka Commons libraries for consistent event production and consumption patterns across services
- Implementing Docker-based end-to-end testing with Testcontainers for reliable integration test environments
- Establishing observability practices with distributed tracing, OpenTelemetry instrumentation, and monitoring dashboards for faster incident resolution

## Third-Party Management

- Managing vendor relationships including onboarding (VPN, GitHub, AWS access), quality oversight, and knowledge transfer
- Transitioning to a blended resourcing model with increasing in-house ownership, reducing dependency on third-party contractors
- Providing clear and continuous feedback on code quality, adherence to coding standards, and defect rates to drive vendor improvement`;

// Build updated skills list: keep existing skills, add new ones
const existingSkills = globalJob.skills || [];
const newSkillNames = ['AWS Glue', 'AWS MSK', 'AWS WAF', 'Avro'];
const existingSkillSet = new Set(existingSkills);
const updatedSkills = [...existingSkills];

for (const skillName of newSkillNames) {
  if (!existingSkillSet.has(skillName)) {
    updatedSkills.push(skillName);
  }
}

db.jobs.updateOne(
  { _id: globalJob._id },
  {
    $set: {
      shortDescription: newShortDescription,
      longDescription: newLongDescription,
      skills: updatedSkills,
      updatedAt: new Date()
    }
  }
);

print('Global job updated: shortDescription, longDescription, skills');
print('Total skills on Global job: ' + updatedSkills.length);

// ============================================================================
// Summary
// ============================================================================

print('');
print('=== Content Update Summary ===');
print('Profile: title, headline, description updated');
print('New skills added: ' + totalNewSkills + ' (AWS Glue, AWS MSK, AWS WAF, Avro)');
print('Global job: shortDescription, longDescription, skills updated');
print('=== Content update complete ===');
