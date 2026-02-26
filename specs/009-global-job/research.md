# Research: Add Global Head of Engineering Job & Skills

**Feature**: 009-global-job | **Date**: 2026-02-26

## R1: Skill Deduplication Against Existing 71 Skills

**Decision**: 7 of the 18 proposed skills already exist in the system and must be skipped. 11 truly new skills will be added to existing groups.

**Rationale**: FR-013 mandates no duplicate skills. Cross-referencing the proposed list against the actual Strapi backup data reveals:

**Already Exists (7 - skip creation, link to Global job only):**

| Skill | Existing Group | Existing Skill Name |
|-------|---------------|-------------------|
| Kafka | Messaging / Events | Kafka |
| Kubernetes | Cloud (also CI/CD) | Kubernetes |
| Docker | CI/CD | Docker |
| Elasticsearch | Cloud (also Data Persistence / Search) | Elastic Search |
| Event Sourcing | Messaging / Events | Event Sourcing |
| CQRS | Messaging / Events | CQRS |
| Helm | Cloud (also CI/CD, Messaging) | Helm |

**Truly New (11 - create and link to Global job):**

| Skill | Target Group | Rationale |
|-------|-------------|-----------|
| Terraform | Cloud | Infrastructure as Code for AWS |
| GraphQL | Web | API query language used across services |
| OpenTelemetry | Cloud | Distributed tracing / observability |
| AWS | Cloud | Generic AWS platform expertise (complements specific AWS-EKS, AWS-S3, etc.) |
| Jenkins | CI/CD | Generic Jenkins expertise (complements Jenkins Pipeline, Jenkins X) |
| GitHub Actions | CI/CD | CI/CD platform used alongside Jenkins |
| Playwright | Testing | E2E test framework |
| OAuth2/OIDC | Identity & Security | Authentication protocols (complements Keycloak, Okta) |
| Material UI | Web | React component library |
| Vite | Web | Frontend build tool |
| Directus | Web | Headless CMS |

**Final counts**: 11 new skills in existing groups + 5 AI skills = **16 new skills total**. 7 existing skills will be linked to the Global job but not recreated.

**Alternatives considered**: Adding all 18 regardless (rejected: violates FR-013) or creating separate entries with qualifiers like "Kafka (Advanced)" (rejected: inconsistent with existing naming).

---

## R2: Existing Skill Groups (Complete Inventory)

**Decision**: Use the actual 9 groups from the Strapi backup as the target for skill placement.

| # | Group Name | Current Skills | Display Order |
|---|-----------|---------------|---------------|
| 1 | Java / Kotlin | Java 8, Java 9-11, Kotlin | 1 |
| 2 | Spring | Spring Boot, Spring Cloud Stream, Spring Data, Spring Security, Spring Cloud Netflix, Spring Cloud Kubernetes, Spring Cloud Vault, Spring Cloud Gateway | 2 |
| 3 | Cloud | AWS-ECS, AWS-Fargate, AWS-CloudFormation, AWS-S3, AWS-EKS, AWS-Route53, AWS-IAM, AWS-Lambda, AWS-RDS, Redis, Cloud Foundry, Elasticsearch, Solr, Kubernetes, Helm | 3 |
| 4 | CI/CD | Maven, Gradle, Jenkins Pipeline, Jenkins X, Concourse, Tekton, Nexus, Docker, Git, Kubernetes, Helm, Ant, SVN | 4 |
| 5 | Data Persistence / Search | Redis, Elastic Search, Solr, MongoDB, DynamoDB, MySQL, Postgres, SQL Server, Oracle | 5 |
| 6 | Testing | Test Containers, Cucumber, Selenium, DBUnit, Mockito, Mockk, Spring Rest Docs, Spring Cloud Contract, TDD, Pact | 6 |
| 7 | Web | Angular, React, Redux, HTML, CSS, Javascript, Typescript | 7 |
| 8 | Messaging / Events | RabbitMQ, Kafka, AWS-SQS, Axon, Event Sourcing, CQRS, Helm, Chart Museum | 8 |
| 9 | Identity & Security | Keycloak, Cloud Foundry UAA, Okta | 9 |

**Note**: Some skills are duplicated across groups (Kubernetes in Cloud + CI/CD, Elasticsearch in Cloud + Data Persistence, etc.). This is by design in the original data.

---

## R3: Image Handling for Global Logo

**Decision**: Copy the Global logo to `backend/uploads/` and create format variants following existing naming conventions. Construct the Image record manually in the migration script.

**Rationale**: Existing images follow the pattern:
- Original: `{filename}.{ext}`
- Thumbnail: `thumbnail_{filename}.{ext}`
- Small: `small_{filename}.{ext}`

The migration script currently sets `formats: null` for all images, so format metadata is not populated in MongoDB. The Global logo can follow the same pattern - store the file, reference it by URL path `/uploads/global-logo.jpg`, and set formats to null (consistent with existing data).

**Alternatives considered**: Processing the image through a resize pipeline to create actual thumbnail/small variants (rejected: existing data doesn't populate format metadata either, so consistency is preferred).

---

## R4: Data Seeding Approach

**Decision**: Create a standalone MongoDB migration script (`scripts/add-global-job-data.js`) that can be run against a live database, and also update the restore pipeline to include this data in future backups.

**Rationale**: The constitution says "Local development seeding MUST use the restore script." However, this feature adds NEW data that doesn't exist in any backup. A dedicated migration script is the cleanest approach:
1. Can be run independently against any environment
2. Is idempotent (checks for existing data before inserting)
3. After running, a new backup can be created to include the data

**Alternatives considered**:
- Modifying the Strapi backup directly (rejected: binary BSON format, fragile)
- Adding data through the content management API (rejected: spec 007 isn't implemented yet)
- Hardcoding in application startup (rejected: violates separation of concerns)

---

## R5: Elasticsearch Search Index Impact

**Decision**: The new job and skills will be indexed automatically by the existing search infrastructure.

**Rationale**: The site-wide search (spec 005) indexes content from MongoDB. When new documents are added to `jobs` and `skill_groups` collections, they will be picked up by the next search index rebuild. No additional code changes are needed for search functionality.

---

## R6: Existing Skills to Link to Global Job

**Decision**: The Global job should link to both existing skills and newly added skills that are relevant to the role.

**Existing skills to link (from the current 71):**

| Group | Skills to Link |
|-------|---------------|
| Java / Kotlin | Java 8, Java 9-11 |
| Spring | Spring Boot, Spring Data, Spring Security, Spring Cloud Kubernetes |
| Cloud | AWS-EKS, AWS-S3, AWS-IAM, AWS-Lambda, Kubernetes, Helm, Elasticsearch |
| CI/CD | Gradle, Jenkins Pipeline, Docker, Git |
| Data Persistence / Search | MongoDB, Elastic Search |
| Testing | Test Containers, TDD |
| Web | React, Javascript, Typescript |
| Messaging / Events | Kafka, RabbitMQ, Event Sourcing, CQRS |
| Identity & Security | (none - existing skills are Keycloak, CF UAA, Okta; not used at Global) |

**New skills to link (all 16):**
- AI group: Claude Code, GitHub Copilot, AI-Assisted Development, Prompt Engineering, MCP
- Existing groups: Terraform, GraphQL, OpenTelemetry, AWS, Jenkins, GitHub Actions, Playwright, OAuth2/OIDC, Material UI, Vite, Directus

**Total skills linked to Global job**: ~38-40 skills (depending on final selection)
