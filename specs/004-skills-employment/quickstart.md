# Quickstart: Skills & Employment

**Feature**: 004-skills-employment | **Date**: 2026-02-21

## Prerequisites

- Docker and Docker Compose installed
- Java 25 JDK installed (for local development without Docker)
- Node.js (latest LTS) and npm installed
- MongoDB backup data migrated (see Data Migration section below)

## Start the Development Environment

```bash
# From repository root
docker compose up -d
```

This starts MongoDB (port 27017), along with any other infrastructure services defined in `docker-compose.yml`.

## Backend Verification

### 1. Build and Run

```bash
cd backend
./gradlew bootRun
```

The backend starts on port 8080 (application) and the configured management port (actuator).

### 2. Verify Skills API

```bash
# Get all skill groups
curl -s http://localhost:8080/api/skills | jq '.[0].name'
# Expected: "Java / Kotlin" (or first group by displayOrder)

# Count skill groups
curl -s http://localhost:8080/api/skills | jq 'length'
# Expected: 9

# Count total skills across all groups
curl -s http://localhost:8080/api/skills | jq '[.[].skills | length] | add'
# Expected: 71

# Get skill group detail with job correlations
curl -s http://localhost:8080/api/skills/5f6359e55ee4c9001d2b9628 | jq '.skills[0].jobs | length'
# Expected: > 0 (Spring Boot is used in multiple jobs)
```

### 3. Verify Jobs API

```bash
# Get all jobs (reverse chronological)
curl -s http://localhost:8080/api/jobs | jq '.[0].title'
# Expected: Most recent job title

# Count jobs
curl -s http://localhost:8080/api/jobs | jq 'length'
# Expected: 9

# Get job detail with resolved skills
curl -s http://localhost:8080/api/jobs/5e53704f11c196001d06f914 | jq '.skills | length'
# Expected: > 0 (Upp Technologies job has many skills)

# Verify job detail includes long description
curl -s http://localhost:8080/api/jobs/5e53704f11c196001d06f914 | jq '.longDescription' | head -1
# Expected: Non-empty markdown string
```

### 4. Verify Resume API

```bash
# Download PDF resume
curl -s -o resume.pdf http://localhost:8080/api/resume

# Verify it is a valid PDF
file resume.pdf
# Expected: "resume.pdf: PDF document, version 1.x"

# Verify file size is reasonable (non-empty)
ls -la resume.pdf
# Expected: File size > 1KB

# Clean up
rm resume.pdf
```

### 5. Run Backend Tests

```bash
cd backend
./gradlew test
```

All tests should pass including:
- `SkillGroupRepositoryTest` -- Testcontainers MongoDB integration
- `SkillGroupServiceTest` -- Business logic and rating aggregation
- `SkillGroupControllerTest` -- REST endpoint contract tests
- `JobRepositoryTest` -- Testcontainers MongoDB integration
- `JobServiceTest` -- Business logic and chronological ordering
- `JobControllerTest` -- REST endpoint contract tests
- `ResumeServiceTest` -- PDF generation validation
- `ResumeControllerTest` -- Download endpoint and content type

## Frontend Verification

### 1. Install Dependencies and Run

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on the Vite dev server (typically port 5173).

### 2. Verify Skills Section

1. Open `http://localhost:5173` in a browser.
2. Scroll to the "My Skills" section.
3. Verify 9 skill group cards are displayed in a grid.
4. Each card shows a category name and representative image.
5. Click on any skill group card (e.g., "Spring").
6. A right-side drawer opens showing:
   - Group name and description
   - All individual skills with name, rating bar, and description
   - Rating bars are color-coded: green (>= 9), blue (8.5-8.9), orange (< 8.5)
   - Job cards below each skill showing where it was used
7. Close the drawer and verify return to the grid.

### 3. Verify Employment Timeline

1. Scroll to the "My Experience" section.
2. Verify 9 timeline entries displayed with alternating left/right layout.
3. Each entry shows company image, job title, and date range.
4. Current positions show "Now" or "Present" instead of an end date.
5. Click on any job entry.
6. A right-side drawer opens with two tabs:
   - **About**: Full markdown description, company name, location, company website link
   - **Skills**: Grid of skill cards used in this position
7. In the Skills tab, click on a skill card.
8. Verify navigation to the skill group detail view for that skill.

### 4. Verify Bidirectional Navigation

1. Open a skill group detail (e.g., "Spring").
2. Find a skill with job correlations (e.g., "Spring Boot").
3. Click on a job card shown below that skill.
4. Verify the job detail drawer opens.
5. Switch to the Skills tab in the job detail.
6. Click on a skill card.
7. Verify the skill group detail drawer opens for the correct group.

### 5. Run Frontend Tests

```bash
cd frontend
npm test
```

All tests should pass including:
- `SkillGroupGrid.test.tsx` -- Renders 9 group cards in correct order
- `SkillGroupDetail.test.tsx` -- Drawer opens, skills listed, color coding correct
- `SkillRatingBar.test.tsx` -- Green/blue/orange thresholds
- `Timeline.test.tsx` -- Alternating layout, 9 entries, date formatting
- `JobDetail.test.tsx` -- Tabbed interface, markdown rendering, skill navigation

## Data Migration

### Import from Strapi Backup

A migration script or manual import is needed to populate the MongoDB instance with data from the Strapi backup at `/Users/simonrowe/backups/strapi-backup-20251116_170434/mongodb/strapi/`.

The migration must:

1. Read `skills_groups.bson`, `skills.bson`, and `jobs.bson` from the backup.
2. Embed skills as subdocuments within their parent skill groups.
3. Resolve image ObjectId references from `upload_file.bson` to inline image objects.
4. Rename Strapi field names (`order` to `displayOrder`, `education` to `isEducation`).
5. Write the transformed documents to the `skill_groups` and `jobs` collections.

### Verify Migration

```bash
# Connect to MongoDB
mongosh

# Check collections
use simonrowe
db.skill_groups.countDocuments()
// Expected: 9

db.skill_groups.aggregate([{$project: {skillCount: {$size: "$skills"}}}, {$group: {_id: null, total: {$sum: "$skillCount"}}}])
// Expected: { total: 71 }

db.jobs.countDocuments()
// Expected: 9

db.jobs.countDocuments({includeOnResume: true})
// Expected: > 0

db.jobs.countDocuments({isEducation: true})
// Expected: >= 1
```

## Success Criteria Verification

| Criterion | How to Verify | Expected |
|-----------|---------------|----------|
| SC-001 | Navigate skills: homepage -> click group -> view skill | 3 clicks or fewer |
| SC-002 | Navigate jobs: homepage -> click entry -> view details | 2 clicks or fewer |
| SC-003 | Click skill -> see jobs -> click job -> see skills -> click skill | Bidirectional links work |
| SC-004 | Time the resume download: `time curl -o /dev/null http://localhost:8080/api/resume` | < 5 seconds |
| SC-005 | Inspect rating colors for skills rated 9+, 8.5-8.9, and < 8.5 | Green, blue, orange respectively |
| SC-006 | Check timeline for education vs employment visual distinction | Entries correctly categorized |
| SC-007 | Open generated PDF, verify only `includeOnResume` jobs appear | No excluded jobs in PDF |
| SC-008 | Compare skill-to-job and job-to-skill links for any skill | Consistent in both directions |
