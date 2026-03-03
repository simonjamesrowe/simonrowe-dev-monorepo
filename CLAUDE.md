# simonrowe-dev-monorepo Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-02-21

## Active Technologies
- Java 25 (backend), TypeScript/React latest stable (frontend) + Spring Boot 4, Spring Data MongoDB, React, React Router, react-markdown (002-profile-homepage)
- MongoDB (profile and social_medias collections) (002-profile-homepage)
- Java 25 (backend), TypeScript (frontend) + Spring Boot 4, Spring Data MongoDB, Spring Data Elasticsearch, React (latest stable), react-markdown, rehype-raw, react-syntax-highlighter (Prism) (003-blog-system)
- MongoDB (primary persistence for blogs and tags), Elasticsearch (search index) (003-blog-system)
- Java 25 (backend), TypeScript (frontend) + Spring Boot 4.x, Spring Data MongoDB, OpenPDF (PDF generation), React (latest stable), react-markdown (markdown rendering) (004-skills-employment)
- MongoDB (skill_groups, skills, jobs collections) (004-skills-employment)
- Java 25 (backend), TypeScript/JavaScript (frontend) + Spring Boot 4.x, Spring Data Elasticsearch, Spring Kafka, Elasticsearch Java client, React (latest stable) (005-site-search)
- Elasticsearch (search indices), MongoDB (source of truth for content data) (005-site-search)
- Java 25 (backend), TypeScript (frontend) + Spring Boot 4.x, Spring Boot Starter Mail (fallback), SendGrid Java SDK (primary email transport), Spring Boot Starter Validation, React (latest stable), react-google-recaptcha (006-contact-form)
- N/A -- contact submissions are not persisted, only forwarded via email (006-contact-form)
- Java 25 (backend), TypeScript (frontend) + Spring Boot 4, Spring Security 6 (OAuth2 Resource Server), Spring Data MongoDB, Auth0 React SDK (`@auth0/auth0-react`), MDXEditor (Markdown editing), Thumbnailator (image resizing) (007-content-management)
- MongoDB (primary persistence), local filesystem or GridFS (media assets) (007-content-management)
- Java 25 (backend), TypeScript (frontend) + Spring Boot 4, Spring Data MongoDB (backend); React latest stable, react-markdown (frontend) (008-interactive-tour)
- MongoDB (tour step documents) (008-interactive-tour)
- Java 21 (backend), TypeScript/React latest stable (frontend) + Spring Boot 3.4.x, Spring Data MongoDB, Spring Kafka, (001-project-infrastructure)
- MongoDB 8 (document store), Elasticsearch 8.17 (search), (001-project-infrastructure)
- Java 21 (LTS) for backend, TypeScript/JavaScript (latest stable) for frontend + Spring Boot 3.5.x, Spring Data MongoDB, Spring Kafka, Spring Data Elasticsearch, Spring Boot Actuator, OpenTelemetry Spring Boot Starter, React (latest stable), Vite (001-project-infrastructure)
- MongoDB (primary persistence), Elasticsearch (search), Kafka (async messaging) (001-project-infrastructure)
- Java 21 (backend), TypeScript (frontend), JavaScript (migration script) + Spring Boot 3.5.x, Spring Data MongoDB, React (latest stable) (009-global-job)
- MongoDB (existing `jobs` and `skill_groups` collections) (009-global-job)
- JavaScript (MongoDB migration script), Java 21 (existing backend, no changes) + MongoDB (data insertion), Elasticsearch (auto-indexed via IndexService) (010-blog-posts)
- MongoDB (`blogs`, `tags`, `skills` collections) (010-blog-posts)

- Java 25 (backend), TypeScript/JavaScript (frontend) + Spring Boot 4.x, Spring Data MongoDB, Spring Kafka, Spring Data Elasticsearch, Spring Boot Actuator, OpenTelemetry, React (latest stable) (001-project-infrastructure)

## Project Structure

```text
src/
tests/
```

## Commands

npm test && npm run lint

## Code Style

Java 25 (backend), TypeScript/JavaScript (frontend): Follow standard conventions

## Recent Changes
- 010-blog-posts: Added JavaScript (MongoDB migration script), Java 21 (existing backend, no changes) + MongoDB (data insertion), Elasticsearch (auto-indexed via IndexService)
- 010-blog-posts: Added JavaScript (MongoDB migration script), Java 21 (existing backend, no changes) + MongoDB (data insertion), Elasticsearch (auto-indexed via IndexService)
- 009-global-job: Added Java 21 (backend), TypeScript (frontend), JavaScript (migration script) + Spring Boot 3.5.x, Spring Data MongoDB, React (latest stable)


<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
