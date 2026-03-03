# Data Model: Add Global Head of Engineering Job & Skills

**Feature**: 009-global-job | **Date**: 2026-02-26

## Overview

This feature adds data to two existing MongoDB collections (`jobs` and `skill_groups`) and introduces one new image file. No schema changes are required — all data conforms to the existing document structures defined in spec 004-skills-employment.

---

## New Document: `jobs` Collection

One new document inserted into the `jobs` collection.

```json
{
  "_id": "ObjectId (auto-generated)",
  "title": "Head of Engineering",
  "company": "Global",
  "companyUrl": "https://global.com",
  "companyImage": {
    "url": "/uploads/global-logo.jpg",
    "name": "global-logo.jpg",
    "width": null,
    "height": null,
    "mime": "image/jpeg",
    "formats": null
  },
  "startDate": "2021-08-01",
  "endDate": null,
  "location": "Holborn, London",
  "shortDescription": "Head of Engineering for Commercial Technology at Global, Europe's largest media and entertainment group.",
  "longDescription": "[Markdown content - see FR-004 sections below]",
  "isEducation": false,
  "includeOnResume": true,
  "skills": ["<array of skill ID strings - see Skill Linkage section>"]
}
```

### Long Description Content Structure (Markdown)

The `longDescription` field will contain markdown covering these sections:

1. **Role Overview** — Head of Engineering for Commercial Technology at Global (Heart, Capital, LBC, Classic FM, Radio X, Smooth, Global Outdoor)
2. **Team Leadership & Management** — Managing multiple engineering teams, recruitment, performance management, career frameworks, Tech Lead development
3. **Architecture & Technical Strategy** — AD groups, cross-team collaboration, BizTalk decommissioning, OIDC auth, feature flags, event bus architecture
4. **Platform & Infrastructure** — gPillar v2 migration (EKS), shared Jenkins pipelines, Terraform modules, Deployment Helper tool, Directus CMS
5. **Product Delivery** — Radio Pillar, Shared Pillar, Outdoor Pillar, Self Service, gCAM, Order Management, gFix, gBam
6. **Engineering Practices** — Shift-left testing, service architecture standards, DDD, CQRS, CI/CD standardization, RBAC/ABAC access control
7. **Third Party Management** — Xdesign, LydTech, ThoughtWorks vendor management

---

## New Document: `skill_groups` Collection

One new document inserted into the `skill_groups` collection for the AI group. Display order = 1 (first position).

```json
{
  "_id": "ObjectId (auto-generated)",
  "name": "AI",
  "description": "Artificial intelligence tools and practices for AI-assisted software development, including coding assistants, prompt engineering, and AI integration protocols.",
  "rating": "<calculated average of child skill ratings>",
  "displayOrder": 1,
  "image": null,
  "skills": [
    {
      "id": "<generated unique ID>",
      "name": "Claude Code",
      "rating": "<owner-assigned, 0-10>",
      "displayOrder": 1,
      "description": "AI-powered coding assistant from Anthropic for autonomous software development, code review, and complex multi-file changes.",
      "image": null
    },
    {
      "id": "<generated unique ID>",
      "name": "GitHub Copilot",
      "rating": "<owner-assigned, 0-10>",
      "displayOrder": 2,
      "description": "AI pair programming tool providing inline code suggestions, completions, and chat-based coding assistance.",
      "image": null
    },
    {
      "id": "<generated unique ID>",
      "name": "AI-Assisted Development",
      "rating": "<owner-assigned, 0-10>",
      "displayOrder": 3,
      "description": "Integrating AI tools into software development workflows to accelerate delivery, improve code quality, and automate repetitive tasks.",
      "image": null
    },
    {
      "id": "<generated unique ID>",
      "name": "Prompt Engineering",
      "rating": "<owner-assigned, 0-10>",
      "displayOrder": 4,
      "description": "Designing effective prompts and instructions for large language models to produce accurate, contextual outputs for development tasks.",
      "image": null
    },
    {
      "id": "<generated unique ID>",
      "name": "MCP",
      "rating": "<owner-assigned, 0-10>",
      "displayOrder": 5,
      "description": "Model Context Protocol — an open standard for connecting AI assistants to external data sources, tools, and services.",
      "image": null
    }
  ]
}
```

---

## Modified Documents: `skill_groups` Collection

### Display Order Updates

All 9 existing skill groups must have their `displayOrder` incremented by 1 to make room for the AI group at position 1:

| Group | Old displayOrder | New displayOrder |
|-------|-----------------|-----------------|
| Java / Kotlin | 1 | 2 |
| Spring | 2 | 3 |
| Cloud | 3 | 4 |
| CI/CD | 4 | 5 |
| Data Persistence / Search | 5 | 6 |
| Testing | 6 | 7 |
| Web | 7 | 8 |
| Messaging / Events | 8 | 9 |
| Identity & Security | 9 | 10 |

### New Skills Added to Existing Groups

11 new skills embedded into existing groups (each as a new entry in the group's `skills[]` array):

| New Skill | Target Group | Skill Object |
|-----------|-------------|--------------|
| Terraform | Cloud | `{id, name: "Terraform", rating: <TBD>, displayOrder: <append>, description: "Infrastructure as Code tool for provisioning and managing cloud resources across AWS, Azure, and GCP.", image: null}` |
| GraphQL | Web | `{id, name: "GraphQL", rating: <TBD>, displayOrder: <append>, description: "Query language and runtime for APIs enabling clients to request exactly the data they need.", image: null}` |
| OpenTelemetry | Cloud | `{id, name: "OpenTelemetry", rating: <TBD>, displayOrder: <append>, description: "Observability framework for generating, collecting, and exporting telemetry data including traces, metrics, and logs.", image: null}` |
| AWS | Cloud | `{id, name: "AWS", rating: <TBD>, displayOrder: <append>, description: "Amazon Web Services cloud platform expertise spanning compute, storage, networking, and managed services.", image: null}` |
| Jenkins | CI/CD | `{id, name: "Jenkins", rating: <TBD>, displayOrder: <append>, description: "Open-source automation server for building, testing, and deploying software through extensible pipelines.", image: null}` |
| GitHub Actions | CI/CD | `{id, name: "GitHub Actions", rating: <TBD>, displayOrder: <append>, description: "CI/CD platform integrated with GitHub for automating build, test, and deployment workflows.", image: null}` |
| Playwright | Testing | `{id, name: "Playwright", rating: <TBD>, displayOrder: <append>, description: "End-to-end testing framework for web applications supporting Chromium, Firefox, and WebKit browsers.", image: null}` |
| OAuth2/OIDC | Identity & Security | `{id, name: "OAuth2/OIDC", rating: <TBD>, displayOrder: <append>, description: "Authentication and authorization protocols for secure service-to-service and user-to-service identity management.", image: null}` |
| Material UI | Web | `{id, name: "Material UI", rating: <TBD>, displayOrder: <append>, description: "React component library implementing Google's Material Design for building consistent, accessible user interfaces.", image: null}` |
| Vite | Web | `{id, name: "Vite", rating: <TBD>, displayOrder: <append>, description: "Next-generation frontend build tool providing fast hot module replacement and optimized production builds.", image: null}` |
| Directus | Web | `{id, name: "Directus", rating: <TBD>, displayOrder: <append>, description: "Open-source headless CMS providing a REST and GraphQL API layer on top of any SQL database.", image: null}` |

### Rating Recalculation

After adding new skills, the `rating` field on each affected group must be recalculated as the average of all child skill ratings:

| Group | Recalculation Needed |
|-------|---------------------|
| Cloud | Yes (+3 skills: Terraform, OpenTelemetry, AWS) |
| CI/CD | Yes (+2 skills: Jenkins, GitHub Actions) |
| Testing | Yes (+1 skill: Playwright) |
| Web | Yes (+4 skills: GraphQL, Material UI, Vite, Directus) |
| Identity & Security | Yes (+1 skill: OAuth2/OIDC) |
| Java / Kotlin | No |
| Spring | No |
| Data Persistence / Search | No |
| Messaging / Events | No |

---

## Skill Linkage: Global Job → Skills

The Global job's `skills[]` array will contain IDs for both existing and new skills:

### Existing Skills to Link (~27 skills)

| Group | Skills |
|-------|--------|
| Java / Kotlin | Java 8, Java 9-11 |
| Spring | Spring Boot, Spring Data, Spring Security, Spring Cloud Kubernetes |
| Cloud | AWS-EKS, AWS-S3, AWS-IAM, AWS-Lambda, Kubernetes, Helm, Elasticsearch |
| CI/CD | Gradle, Jenkins Pipeline, Docker, Git |
| Data Persistence / Search | MongoDB, Elastic Search |
| Testing | Test Containers, TDD |
| Web | React, Javascript, Typescript |
| Messaging / Events | Kafka, RabbitMQ, Event Sourcing, CQRS |

### New Skills to Link (16 skills)

| Group | Skills |
|-------|--------|
| AI | Claude Code, GitHub Copilot, AI-Assisted Development, Prompt Engineering, MCP |
| Cloud | Terraform, OpenTelemetry, AWS |
| CI/CD | Jenkins, GitHub Actions |
| Testing | Playwright |
| Web | GraphQL, Material UI, Vite, Directus |
| Identity & Security | OAuth2/OIDC |

**Total skills linked**: ~43 skills across 10 groups (all 10 groups represented → satisfies SC-006 requirement of at least 4 groups)

---

## New File: Image Asset

| File | Source | Destination |
|------|--------|-------------|
| Global logo | `specs/009-global-job/attachments/global-logo.jpg` | `backend/uploads/global-logo.jpg` |

No thumbnail/small variants will be generated (consistent with existing migration behavior where `formats` is set to null).

---

## No Schema Changes

This feature requires zero changes to:
- Java model classes (`Job.java`, `SkillGroup.java`, `Skill.java`, `Image.java`)
- Repository interfaces
- Service classes
- Controller classes
- Frontend TypeScript types
- Frontend React components
- API contracts
