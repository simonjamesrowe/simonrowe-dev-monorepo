// Migration script: Add Global Head of Engineering job and skills
// Runs via mongosh inside the MongoDB container
// Idempotent — safe to run multiple times

const db = db.getSiblingDB('simonrowe');

// ============================================================================
// Idempotency checks
// ============================================================================

const existingGlobalJob = db.jobs.findOne({ company: 'Global' });
const existingAiGroup = db.skill_groups.findOne({ name: 'Artificial Intelligence' });

// ============================================================================
// Fix: Repair any skills with missing _id (from earlier .str bug)
// ============================================================================

print('--- Checking for skills with missing _id ---');
let repairCount = 0;
db.skill_groups.find().forEach(group => {
  const skills = group.skills || [];
  let modified = false;
  skills.forEach(skill => {
    if (!skill._id) {
      skill._id = new ObjectId();
      modified = true;
      repairCount++;
    }
  });
  if (modified) {
    db.skill_groups.updateOne({ _id: group._id }, { $set: { skills: skills } });
    print('  Repaired skills in group: ' + group.name);
  }
});
if (repairCount > 0) {
  print('Repaired ' + repairCount + ' skills with missing _id');
} else {
  print('All skills have valid _id');
}

if (existingGlobalJob && existingAiGroup) {
  print('--- Global job and Artificial Intelligence group already exist. Skipping migration. ---');
  // Still verify skill linkage
  const skillCount = (existingGlobalJob.skills || []).length;
  print('Global job has ' + skillCount + ' linked skills');
  print('--- Migration check complete ---');
  quit();
}

print('=== Adding Global Head of Engineering job and skills ===');
print('');

// ============================================================================
// Phase 1: Insert AI skill group at display order 1
// ============================================================================

if (!existingAiGroup) {
  print('--- Shifting existing skill group display orders ---');
  db.skill_groups.updateMany({}, { $inc: { displayOrder: 1 } });
  print('Shifted ' + db.skill_groups.countDocuments() + ' groups');

  print('--- Inserting AI skill group ---');

  const aiSkills = [
    {
      _id: new ObjectId(),
      name: 'Claude Code',
      rating: 8,
      displayOrder: 1,
      description: 'AI-powered coding assistant from Anthropic for autonomous software development, code review, and complex multi-file changes.',
      image: { url: '/uploads/claude_code.png', name: 'claude_code.png', width: 200, height: 200, mime: 'image/png', formats: null }
    },
    {
      _id: new ObjectId(),
      name: 'GitHub Copilot',
      rating: 8,
      displayOrder: 2,
      description: 'AI pair programming tool providing inline code suggestions, completions, and chat-based coding assistance.',
      image: { url: '/uploads/github_copilot.png', name: 'github_copilot.png', width: 200, height: 200, mime: 'image/png', formats: null }
    },
    {
      _id: new ObjectId(),
      name: 'AI-Assisted Development',
      rating: 9,
      displayOrder: 3,
      description: 'Integrating AI tools into software development workflows to accelerate delivery, improve code quality, and automate repetitive tasks.',
      image: null
    },
    {
      _id: new ObjectId(),
      name: 'Prompt Engineering',
      rating: 8,
      displayOrder: 4,
      description: 'Designing effective prompts and instructions for large language models to produce accurate, contextual outputs for development tasks.',
      image: null
    },
    {
      _id: new ObjectId(),
      name: 'MCP',
      rating: 7,
      displayOrder: 5,
      description: 'Model Context Protocol \u2014 an open standard for connecting AI assistants to external data sources, tools, and services.',
      image: null
    }
  ];

  const aiRating = aiSkills.reduce((sum, s) => sum + s.rating, 0) / aiSkills.length;

  db.skill_groups.insertOne({
    name: 'Artificial Intelligence',
    description: 'Artificial intelligence tools and practices for AI-assisted software development, including coding assistants, prompt engineering, and AI integration protocols.',
    rating: aiRating,
    displayOrder: 1,
    image: null,
    skills: aiSkills
  });

  print('Artificial Intelligence group inserted with ' + aiSkills.length + ' skills, rating: ' + aiRating);
} else {
  print('--- Artificial Intelligence group already exists, skipping ---');
}

// ============================================================================
// Phase 2: Add new skills to existing groups
// ============================================================================

print('');
print('--- Adding new skills to existing groups ---');

const newSkillsByGroup = {
  'Java / Kotlin': [
    {
      name: 'Java 17',
      rating: 9,
      description: 'Long-term support release of Java featuring sealed classes, pattern matching for switch, and enhanced pseudo-random number generators.',
      image: { url: '/uploads/java_17.png', name: 'java_17.png', width: 400, height: 225, mime: 'image/png', formats: null }
    },
    {
      name: 'Java 21',
      rating: 8,
      description: 'Latest LTS release of Java introducing virtual threads, record patterns, pattern matching for switch, and sequenced collections.',
      image: { url: '/uploads/java_21.png', name: 'java_21.png', width: 400, height: 225, mime: 'image/png', formats: null }
    }
  ],
  'Cloud': [
    {
      name: 'Terraform',
      rating: 7,
      description: 'Infrastructure as Code tool for provisioning and managing cloud resources across AWS, Azure, and GCP.',
      image: { url: '/uploads/terraform.png', name: 'terraform.png', width: 250, height: 250, mime: 'image/png', formats: null }
    },
    {
      name: 'OpenTelemetry',
      rating: 7,
      description: 'Observability framework for generating, collecting, and exporting telemetry data including traces, metrics, and logs.',
      image: { url: '/uploads/opentelemetry.png', name: 'opentelemetry.png', width: 250, height: 250, mime: 'image/png', formats: null }
    },
    {
      name: 'AWS',
      rating: 8,
      description: 'Amazon Web Services cloud platform expertise spanning compute, storage, networking, and managed services.',
      image: { url: '/uploads/aws.png', name: 'aws.png', width: 200, height: 120, mime: 'image/png', formats: null }
    }
  ],
  'CI/CD': [
    {
      name: 'Jenkins',
      rating: 9,
      description: 'Open-source automation server for building, testing, and deploying software through extensible pipelines.',
      image: { url: '/uploads/jenkins.png', name: 'jenkins.png', width: 256, height: 256, mime: 'image/png', formats: null }
    },
    {
      name: 'GitHub Actions',
      rating: 7,
      description: 'CI/CD platform integrated with GitHub for automating build, test, and deployment workflows.',
      image: { url: '/uploads/github_actions.png', name: 'github_actions.png', width: 200, height: 200, mime: 'image/png', formats: null }
    }
  ],
  'Testing': [
    {
      name: 'Playwright',
      rating: 7,
      description: 'End-to-end testing framework for web applications supporting Chromium, Firefox, and WebKit browsers.',
      image: { url: '/uploads/playwright.png', name: 'playwright.png', width: 250, height: 250, mime: 'image/png', formats: null }
    }
  ],
  'Web': [
    {
      name: 'GraphQL',
      rating: 6,
      description: 'Query language and runtime for APIs enabling clients to request exactly the data they need.',
      image: { url: '/uploads/graphql.png', name: 'graphql.png', width: 200, height: 200, mime: 'image/png', formats: null }
    },
    {
      name: 'Material UI',
      rating: 6,
      description: 'React component library implementing Google\'s Material Design for building consistent, accessible user interfaces.',
      image: { url: '/uploads/material_ui.png', name: 'material_ui.png', width: 250, height: 250, mime: 'image/png', formats: null }
    },
    {
      name: 'Vite',
      rating: 7,
      description: 'Next-generation frontend build tool providing fast hot module replacement and optimized production builds.',
      image: { url: '/uploads/vite.png', name: 'vite.png', width: 200, height: 198, mime: 'image/png', formats: null }
    },
    {
      name: 'Directus',
      rating: 7,
      description: 'Open-source headless CMS providing a REST and GraphQL API layer on top of any SQL database.',
      image: { url: '/uploads/directus.png', name: 'directus.png', width: 200, height: 200, mime: 'image/jpeg', formats: null }
    }
  ],
  'Identity & Security': [
    {
      name: 'OAuth2/OIDC',
      rating: 8,
      description: 'Authentication and authorization protocols for secure service-to-service and user-to-service identity management.',
      image: { url: '/uploads/oauth2_oidc.png', name: 'oauth2_oidc.png', width: 250, height: 96, mime: 'image/png', formats: null }
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

  const existingSkillNames = (group.skills || []).map(s => s.name);
  const maxOrder = Math.max(0, ...(group.skills || []).map(s => s.displayOrder || 0));

  let addedCount = 0;
  let orderOffset = 0;

  for (const skill of skills) {
    if (existingSkillNames.includes(skill.name)) {
      print('  Skill "' + skill.name + '" already exists in ' + groupName + ', skipping');
      continue;
    }

    orderOffset++;
    const newSkill = {
      _id: new ObjectId(),
      name: skill.name,
      rating: skill.rating,
      displayOrder: maxOrder + orderOffset,
      description: skill.description,
      image: skill.image || null
    };

    db.skill_groups.updateOne(
      { _id: group._id },
      { $push: { skills: newSkill } }
    );
    addedCount++;
    totalNewSkills++;
  }

  // Recalculate group rating
  if (addedCount > 0) {
    const updatedGroup = db.skill_groups.findOne({ _id: group._id });
    const allSkills = updatedGroup.skills || [];
    const avgRating = allSkills.reduce((sum, s) => sum + (s.rating || 0), 0) / allSkills.length;
    const roundedRating = Math.round(avgRating * 10) / 10;

    db.skill_groups.updateOne(
      { _id: group._id },
      { $set: { rating: roundedRating } }
    );

    print('  ' + groupName + ': added ' + addedCount + ' skills, new rating: ' + roundedRating);
  } else {
    print('  ' + groupName + ': no new skills added');
  }
}

print('Total new skills added to existing groups: ' + totalNewSkills);

// ============================================================================
// Phase 2b: Reorder skills within groups (recency emphasis)
// ============================================================================

print('');
print('--- Reordering skills within groups ---');

// Defines preferred display order per group. Skills listed first appear first.
// Any skill not listed retains its relative position after the listed ones.
const skillOrdering = {
  'Java / Kotlin': [
    'Java 21', 'Java 17', 'Java 9-11', 'Java 8', 'Kotlin'
  ],
  'Spring': [
    'Spring Boot', 'Spring Security', 'Spring Data', 'Spring Cloud Kubernetes',
    'Spring Cloud Gateway', 'Spring Cloud Stream', 'Spring Cloud Vault', 'Spring Cloud Netflix'
  ],
  'Cloud': [
    'AWS', 'Kubernetes', 'Terraform', 'AWS - EKS', 'Helm', 'OpenTelemetry',
    'AWS - Lambda', 'AWS - IAM', 'AWS - S3', 'AWS - SQS',
    'AWS - ECS', 'AWS - Fargate', 'AWS - RDS', 'AWS - CloudFormation',
    'AWS - Route53', 'Redis', 'Cloud Foundry', 'Chart Museum'
  ],
  'CI/CD': [
    'Jenkins', 'GitHub Actions', 'Docker', 'Git', 'Gradle', 'Jenkins Pipeline',
    'Helm', 'Maven', 'Tekton', 'Concourse', 'Nexus', 'Jenkins X',
    'Chart Museum', 'Ant', 'SVN'
  ],
  'Data Persistence / Search': [
    'MongoDB', 'Elastic Search', 'Postgres', 'Redis', 'DynamoDB',
    'MySQL', 'SQL Server', 'Oracle', 'Solr'
  ],
  'Testing': [
    'Playwright', 'Test Containers', 'TDD', 'Mockito', 'Pact', 'Mockk',
    'Spring Rest Docs', 'JMeter', 'Cucumber', 'Selenium', 'DBUnit'
  ],
  'Web': [
    'React', 'Typescript', 'Vite', 'Material UI', 'GraphQL',
    'Javascript', 'Directus', 'Angular', 'HTML', 'CSS'
  ],
  'Messaging / Events': [
    'Kafka', 'Event Sourcing', 'RabbitMQ', 'AWS - SQS'
  ],
  'Identity & Security': [
    'OAuth2/OIDC', 'Keycloak', 'Okta', 'Cloud Foundry UAA'
  ]
};

let reorderedGroups = 0;
for (const [groupName, orderedNames] of Object.entries(skillOrdering)) {
  const group = db.skill_groups.findOne({ name: groupName });
  if (!group) continue;

  const skills = group.skills || [];
  const nameToIndex = {};
  orderedNames.forEach((name, i) => { nameToIndex[name] = i + 1; });

  // Assign new displayOrder: listed skills get their defined position,
  // unlisted skills get appended after in their original relative order
  const maxDefined = orderedNames.length;
  let unlistedOffset = 0;
  const updatedSkills = skills.map(s => {
    const definedOrder = nameToIndex[s.name];
    if (definedOrder) {
      return Object.assign({}, s, { displayOrder: definedOrder });
    }
    unlistedOffset++;
    return Object.assign({}, s, { displayOrder: maxDefined + unlistedOffset });
  });

  db.skill_groups.updateOne(
    { _id: group._id },
    { $set: { skills: updatedSkills } }
  );
  reorderedGroups++;
}

print('Reordered skills in ' + reorderedGroups + ' groups');

// ============================================================================
// Phase 3: Resolve skill names for the Global job
// ============================================================================

print('');
print('--- Resolving skill IDs for Global job ---');

// Skills to link (by name) — both existing and newly created
const skillNamesToLink = [
  // Artificial Intelligence group (all 5)
  'Claude Code', 'GitHub Copilot', 'AI-Assisted Development', 'Prompt Engineering', 'MCP',
  // Java / Kotlin
  'Java 8', 'Java 9-11', 'Java 17', 'Java 21',
  // Spring
  'Spring Boot', 'Spring Data', 'Spring Security', 'Spring Cloud Kubernetes',
  // Cloud (existing + new)
  'AWS - EKS', 'AWS - S3', 'AWS - IAM', 'AWS - Lambda', 'Kubernetes', 'Helm',
  'Terraform', 'OpenTelemetry', 'AWS',
  // CI/CD (existing + new)
  'Gradle', 'Jenkins Pipeline', 'Docker', 'Git',
  'Jenkins', 'GitHub Actions',
  // Data Persistence / Search
  'MongoDB', 'Elastic Search',
  // Testing (existing + new)
  'Test Containers', 'TDD',
  'Playwright',
  // Web (existing + new)
  'React', 'Javascript', 'Typescript',
  'GraphQL', 'Material UI', 'Vite', 'Directus',
  // Messaging / Events
  'Kafka', 'RabbitMQ', 'Event Sourcing',
  // Identity & Security (new)
  'OAuth2/OIDC'
];

// The job service resolves skills by name, so we store names in the skills array
// (consistent with how migrate-strapi-data.js stores them)
const resolvedSkills = [];
const allGroups = db.skill_groups.find().toArray();
const skillNameSet = new Set(skillNamesToLink);
const groupSkillCounts = {};

for (const group of allGroups) {
  let count = 0;
  for (const skill of (group.skills || [])) {
    if (skillNameSet.has(skill.name)) {
      resolvedSkills.push(skill.name);
      count++;
    }
  }
  if (count > 0) {
    groupSkillCounts[group.name] = count;
  }
}

print('Resolved ' + resolvedSkills.length + ' of ' + skillNamesToLink.length + ' target skills');

// Report any unresolved skills
const resolvedSet = new Set(resolvedSkills);
const unresolved = skillNamesToLink.filter(n => !resolvedSet.has(n));
if (unresolved.length > 0) {
  print('WARNING: Could not resolve: ' + unresolved.join(', '));
}

// Report skills per group
print('Skills per group:');
for (const [group, count] of Object.entries(groupSkillCounts)) {
  print('  ' + group + ': ' + count);
}

// ============================================================================
// Phase 4: Insert the Global job
// ============================================================================

print('');

if (!existingGlobalJob) {
  print('--- Inserting Global Head of Engineering job ---');

  const longDescription = `## Role Overview

Head of Engineering for Commercial Technology at Global, Europe's largest media and entertainment group. Responsible for engineering strategy, team leadership, and delivery across Global's commercial trading platforms serving brands including Heart, Capital, LBC, Classic FM, Radio X, Smooth, and Global Outdoor (now Global Media & Entertainment).

## Team Leadership & Management

- Managing multiple engineering squads comprising permanent developers and third-party contractors across fulfilment, shared services, and self-service product areas
- Recruiting and onboarding Java engineers, growing the team with senior hires and establishing a stronger in-house engineering presence
- Developing and mentoring three Tech Leads (fulfilment, shared services, and self-service), providing coaching on technical decisions, team management, and career growth
- Designing and implementing a career framework for individual contributors and technical leaders with mapped skill sets and behavioural expectations
- Driving team engagement through recognition, inclusive decision-making, and creating opportunities for engineers to lead knowledge-sharing sessions and strategic initiatives
- Performance management including regular 1:1s, constructive feedback, and supporting professional development across the team

## Architecture & Technical Strategy

- Restructuring AD groups and collaboration frameworks to enable cross-team code contribution, reducing hard dependencies between squads
- Architecting the decommissioning of legacy BizTalk systems by deploying new Java services, eliminating outages and reducing support overhead
- Implementing federated OIDC authentication for external API calls, removing credential rotation concerns through service-to-service authentication using Kubernetes service account tokens
- Introducing feature flags via Spring Cloud for controlled rollouts without redeployment
- Designing event-driven integration architecture using Kafka and Schema Registry for choreographed communication between order management, fulfilment, and planning systems
- Establishing shared technical standards covering service architecture (DDD, CQRS, pub-sub), deployment models, and access control (RBAC/ABAC)

## Platform & Infrastructure

- Leading migration of workloads to gPillar v2 on AWS EKS, including the first services onto the new platform with coordinated infrastructure setup and networking
- Building shared Jenkins CI/CD pipelines and Terraform modules to standardise deployment across teams, including pipeline-based load testing
- Creating the Deployment Helper tool (adopted and enhanced by other teams) to streamline deployment workflows and reduce minimum deployment times
- Setting up infrastructure for Directus CMS, managing network ACL configurations for Open Direct API, and coordinating platform access for third-party teams
- Enhancing the local development environment in collaboration with Tech Leads to improve developer experience across commercial trading engineering

## Product Delivery

- **Radio Pillar**: Availability for decision engine, gplan radio API, buying area service, performance improvements, pricing service replacement, creative instructions
- **Shared Pillar**: New pricing service, Open Direct API, billing iterations (xola & OM), self-service features (advert pillar, OPD/Unlimited, production only, digital booking enhancements, gblocks, print automation)
- **Outdoor Pillar**: Managed the fulfilment engineering function through creative asset management, Delta integration, and production delivery
- **Self Service**: Established engineering structures enabling in-house and external developer collaboration, growing the platform from concept through to launch readiness
- **Other Initiatives**: gCAM, order management enhancements, gFix, gBam, TFL copy approval, 24/7 booking support, and protector flux allocation for gRelease

## Engineering Practices

- Championing a shift-left testing approach with integration testing using Playwright and JUnit/RestAssured, building a culture where quality is every developer's responsibility
- Running knowledge-sharing sessions and ad-hoc group coaching on Java, unit testing, Spring Boot, Kubernetes, Helm, and CI/CD practices
- Standardising service architecture across teams with documented principles covering event-driven design, clean coding, DDD, and MVC patterns
- Driving adoption of AI development tools including GitHub Copilot to accelerate development efficiency
- Implementing observability practices with distributed tracing and monitoring to enable faster incident resolution and more confident releases

## Third-Party Management

- Managing vendor relationships with Xdesign, LydTech, and ThoughtWorks including onboarding (VPN, GitHub, AWS access), quality oversight, and knowledge transfer
- Transitioning to a blended resourcing model with increasing in-house ownership, reducing dependency on third-party contractors
- Providing clear and continuous feedback on code quality, adherence to coding standards, and defect rates to drive vendor improvement`;

  db.jobs.insertOne({
    title: 'Head of Engineering',
    company: 'Global',
    companyUrl: 'https://global.com',
    companyImage: {
      url: '/uploads/global-logo.jpg',
      name: 'global-logo.jpg',
      width: null,
      height: null,
      mime: 'image/jpeg',
      formats: null
    },
    startDate: '2021-08-01',
    endDate: null,
    location: 'Holborn, London',
    shortDescription: 'Head of Engineering for Commercial Technology at Global, Europe\'s largest media and entertainment group.',
    longDescription: longDescription,
    isEducation: false,
    includeOnResume: true,
    skills: resolvedSkills
  });

  print('Global job inserted with ' + resolvedSkills.length + ' linked skills');
} else {
  // Job exists but may need skills updated
  if (resolvedSkills.length > 0 && (existingGlobalJob.skills || []).length === 0) {
    db.jobs.updateOne(
      { _id: existingGlobalJob._id },
      { $set: { skills: resolvedSkills } }
    );
    print('Updated existing Global job with ' + resolvedSkills.length + ' linked skills');
  } else {
    print('Global job already exists with ' + (existingGlobalJob.skills || []).length + ' skills');
  }
}

// ============================================================================
// Summary
// ============================================================================

print('');
print('=== Migration Summary ===');
print('Total jobs: ' + db.jobs.countDocuments());
print('Total skill groups: ' + db.skill_groups.countDocuments());
print('Global job skills: ' + resolvedSkills.length);
print('Groups with linked skills: ' + Object.keys(groupSkillCounts).length);
print('=== Migration complete ===');
