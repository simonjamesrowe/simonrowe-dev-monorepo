# Feature Specification: Add Global Head of Engineering Job & Skills

**Feature Branch**: `009-global-job`
**Created**: 2026-02-26
**Status**: Draft
**Input**: User description: "Add new job title as Head of Engineering at Global with company icon, dates Aug 2021 - current, location Holborn London. Add new AI skill group and new skills to existing skill groups from this role."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View Global Head of Engineering Position on Timeline (Priority: P1)

A visitor browses the employment timeline and sees the Head of Engineering position at Global displayed as the most recent (current) role. The entry shows the Global logo, job title "Head of Engineering", dates "August 2021 - Present", and the location "Holborn, London". Clicking on the entry reveals a detailed view with an "About" tab describing the role's responsibilities and achievements across team leadership, architecture, platform infrastructure, product delivery, and engineering practices at one of Europe's largest media and entertainment companies. A "Skills" tab lists all technical skills utilized in this role, linking back to the skills section for cross-referencing.

**Why this priority**: The job entry is the core deliverable of this feature. Without it, no new employment data is visible and the other stories (skills, correlations) have no anchor point.

**Independent Test**: Can be fully tested by navigating to the employment timeline, confirming the Global entry appears at the top as the current position, clicking it, and verifying the About tab renders the full markdown description and the Skills tab lists all associated skills. A link to global.com is present and clickable.

**Acceptance Scenarios**:

1. **Given** a visitor is on the employment timeline, **When** the page loads, **Then** the Global "Head of Engineering" entry appears as the most recent position at the top of the timeline with the Global logo, title, "Aug 2021 - Present" dates, and "Holborn, London" location
2. **Given** a visitor clicks on the Global job entry, **When** the detail view opens, **Then** the "About" tab displays a comprehensive markdown-rendered description covering team leadership, technical strategy, platform infrastructure, product delivery, and engineering practices
3. **Given** a visitor is viewing the Global job detail, **When** they click the "Skills" tab, **Then** all technical skills associated with this role are listed with their names, ratings, and skill group context
4. **Given** a visitor views the Global job detail, **When** they look for the company link, **Then** a clickable link to global.com is displayed
5. **Given** the Global position has no end date, **When** displayed on the timeline and in details, **Then** it is clearly indicated as a current/ongoing position showing "Present"
6. **Given** the Global job has includeOnResume set to true, **When** a visitor downloads the PDF resume, **Then** the Head of Engineering position at Global appears in the employment section

---

### User Story 2 - Browse New AI Skill Group (Priority: P2)

A visitor navigates to the skills section and discovers a new "AI" skill category group alongside the existing 9 groups. The AI group displays a representative name, aggregated proficiency rating, and image. Clicking on the group reveals individual AI-related skills including Claude Code, GitHub Copilot, AI-Assisted Development, Prompt Engineering, and MCP (Model Context Protocol), each with their own rating and description.

**Why this priority**: The AI skill group is entirely new content that showcases emerging expertise. It is independently valuable and demonstrates forward-looking technical capabilities.

**Independent Test**: Can be tested by navigating to the skills page, confirming 10 skill groups now appear (up from 9), clicking on the AI group, and verifying all individual AI skills are listed with ratings, descriptions, and proper display ordering.

**Acceptance Scenarios**:

1. **Given** a visitor is on the skills page, **When** the page loads, **Then** 10 skill category groups are displayed in the grid (up from the previous 9), with the AI group visible in its defined display position
2. **Given** a visitor clicks on the AI skill group, **When** the detail view opens, **Then** individual skills are listed including Claude Code, GitHub Copilot, AI-Assisted Development, Prompt Engineering, and MCP
3. **Given** a visitor views AI skills, **When** reviewing each skill, **Then** each displays a name, proficiency rating with appropriate color coding, and a description explaining the skill
4. **Given** the AI skill group has an aggregated rating, **When** displayed in the skills grid, **Then** the group rating accurately reflects the average of its individual skill ratings

---

### User Story 3 - Discover New Skills Added to Existing Groups (Priority: P3)

A visitor browses existing skill groups and finds new individual skills that have been added from the Global role. Skills such as Kafka, Terraform, Kubernetes, GraphQL, Event Sourcing, Elasticsearch, Docker, and others appear within their appropriate existing skill groups. Each new skill has a proficiency rating, description, and is linked to the Global job (and potentially other relevant jobs).

**Why this priority**: Enriching existing skill groups with new skills from the Global role adds depth to the portfolio. This depends on having the correct skill groups already in place and builds on the existing structure rather than replacing it.

**Independent Test**: Can be tested by clicking into existing skill groups and confirming new skills appear that were not previously there, each with complete data including rating, description, and job correlations.

**Acceptance Scenarios**:

1. **Given** a visitor opens an existing skill group, **When** the detail view loads, **Then** any newly added skills appear in the correct display order alongside pre-existing skills
2. **Given** a visitor views a newly added skill (e.g., Kafka), **When** they check its job correlations, **Then** the Global Head of Engineering position appears as a related job
3. **Given** new skills have been added to multiple existing groups, **When** a visitor browses all skill groups, **Then** the total skill count has increased from 71 and each group's aggregated rating reflects the updated skill set
4. **Given** a visitor views the Global job's Skills tab, **When** the skills list loads, **Then** it includes both pre-existing skills and newly added skills that are relevant to this role

---

### User Story 4 - Cross-Reference Global Job with Skills (Priority: P4)

A visitor exploring skill details discovers that the Global Head of Engineering position appears as a related job for numerous skills across multiple groups. Conversely, when viewing the Global job's Skills tab, a comprehensive list of skills spans across categories including AI, Java, DevOps, Architecture, and Frontend technologies, creating a rich bidirectional narrative of the role's technical scope.

**Why this priority**: Cross-referencing depends on both the job and skills being in place. It enriches the professional narrative but is the final integration layer, not independently essential.

**Independent Test**: Can be tested by viewing any skill detail that is linked to the Global job and confirming the job card appears, then navigating to the Global job's Skills tab and confirming it lists skills from multiple skill groups. Bidirectional navigation works between both views.

**Acceptance Scenarios**:

1. **Given** a visitor views a skill that is linked to the Global job, **When** the skill detail loads, **Then** a job card for "Head of Engineering - Global" appears showing title, company, and date range
2. **Given** a visitor is on the Global job's Skills tab, **When** they click on a skill, **Then** they navigate to that skill's full detail view within its parent skill group
3. **Given** the Global job references skills from multiple skill groups, **When** a visitor views the Skills tab, **Then** skills from the AI group, existing Java/Spring groups, DevOps groups, and other relevant categories are all represented
4. **Given** a visitor navigates from a skill to the Global job and back, **When** following the bidirectional links, **Then** the correlation data is consistent in both directions

---

### Edge Cases

- What happens when the Global logo image file is missing or cannot be loaded? The system displays a placeholder image consistent with how other jobs handle missing company images.
- How does the system handle the Global job appearing at the top of the timeline when it has no end date? It displays "Present" and is sorted as the most recent entry.
- What happens if a skill ID referenced by the Global job does not exist in any skill group? The system gracefully omits that skill from the job's Skills tab rather than displaying an error.
- How does the system handle the new AI skill group having fewer skills than other established groups? It displays normally regardless of the number of skills.
- What happens when the same skill is relevant to both the Global job and a previously existing job? The skill's job correlation view lists both jobs in chronological order.
- How does the PDF resume render the Global job's lengthy description without layout overflow? The description is appropriately truncated or paginated within the existing PDF layout constraints.

## Requirements *(mandatory)*

### Functional Requirements

#### Job Data Requirements

- **FR-001**: System MUST add a new job entry for "Head of Engineering" at Global with company "Global", company URL "global.com", start date "2021-08-01", no end date (current position), location "Holborn, London", includeOnResume true, and isEducation false
- **FR-002**: System MUST store the Global company logo as the job's company image with appropriate format variants (thumbnail, small)
- **FR-003**: System MUST include a short description suitable for timeline display summarizing the role as Head of Engineering for Commercial Technology at a major media and entertainment company
- **FR-004**: System MUST include a long description in markdown format covering: team leadership and management, architecture and technical strategy, platform and infrastructure, product delivery, and engineering practices
- **FR-005**: System MUST associate the Global job with relevant skill IDs spanning multiple skill groups including the new AI group and existing groups
- **FR-006**: System MUST display the Global job at the top of the employment timeline as the most recent and current position, increasing the total job count from 9 to 10

#### New Skill Group Requirements

- **FR-007**: System MUST add a new "AI" skill category group with a representative name, description, aggregated rating, image, and display order set to position 1 (first in the skills grid), shifting existing groups' display orders accordingly, increasing the total skill group count from 9 to 10
- **FR-008**: System MUST include the following individual skills within the AI group: Claude Code, GitHub Copilot, AI-Assisted Development, Prompt Engineering, and MCP (Model Context Protocol)
- **FR-009**: Each AI skill MUST have a name, proficiency rating (0-10 scale), display order, and description
- **FR-010**: The AI skill group's aggregated rating MUST be calculated from its individual skill ratings

#### New Skills in Existing Groups Requirements

- **FR-011**: System MUST add exactly the following 18 new individual skills to appropriate existing skill groups (after deduplication against existing 71 skills): Kafka, Terraform, Kubernetes, GraphQL, Event Sourcing, CQRS, Elasticsearch, Docker, Helm, OpenTelemetry, AWS, Jenkins, GitHub Actions, Playwright, OAuth2/OIDC, Material UI, Vite, and Directus. Combined with the 5 AI group skills, this feature adds a fixed set of 23 new skills total.
- **FR-012**: Each newly added skill MUST have a name, proficiency rating, display order within its parent group, and description
- **FR-013**: System MUST NOT duplicate skills that already exist in the system; new skills must be cross-referenced against the existing 71 skills before addition
- **FR-014**: Each existing skill group's aggregated rating MUST be recalculated if new skills are added to it

#### Skill-Job Correlation Requirements

- **FR-015**: System MUST link the Global job to all relevant skill IDs including both pre-existing skills and newly added skills. Existing jobs' skill lists MUST NOT be modified; new skills are only associated with the Global job.
- **FR-016**: System MUST ensure bidirectional consistency: every skill referenced by the Global job must appear in the job's Skills tab, and the Global job must appear in each linked skill's job correlations

### Key Entities

- **Job (Global - Head of Engineering)**: A new employment entry representing the current role at Global, Europe's largest media and entertainment group. Key attributes: title "Head of Engineering", company "Global", website global.com, start date August 2021, no end date (current), location Holborn London, detailed markdown description of responsibilities and achievements, Global logo as company image, flagged for resume inclusion, linked to numerous skills across multiple categories.

- **Skill Group (AI)**: A new skill category representing artificial intelligence and AI-assisted development capabilities. Contains skills related to AI coding assistants, prompt engineering, and AI integration protocols. Has an aggregated rating and display order within the skills grid.

- **New Skills**: Individual technical competencies added across the AI group and existing skill groups, each representing a technology or practice utilized in the Global Head of Engineering role. Each skill has a proficiency rating, description, and cross-references to relevant jobs.

## Clarifications

### Session 2026-02-26

- Q: Should new skills also be linked to pre-existing jobs where relevant? → A: Global job only - only link new skills to the Global job, leave existing jobs unchanged.
- Q: Is the new skills list in FR-011 exhaustive or open-ended? → A: Fixed list - the 18 skills in FR-011 plus the 5 AI skills (23 total) are the complete set for this feature.
- Q: Where should the AI skill group appear in the display order? → A: First position - AI appears at the top of the skills grid for maximum emphasis.

## Assumptions

- The Global company logo provided in the attachments (specs/009-global-job/attachments/global-logo.jpg) will be used as the company image and processed into the required format variants (thumbnail, small, etc.).
- The existing 9 skill groups cover broad categories (e.g., Java/Kotlin, Spring, JavaScript, DevOps, etc.) and the new skills listed in FR-011 will be placed in the most appropriate existing group based on their domain.
- Skills that already exist in the system (e.g., if "Docker" or "AWS" are already present) will not be duplicated; only genuinely new skills will be added.
- The long description for the Global job will be authored based on the Spotlight review content (2023, 2024, 2025) and GitHub repository analysis, covering the key responsibility areas outlined in FR-004.
- Proficiency ratings for new skills will be assigned by the site owner based on self-assessment, consistent with the existing 0-10 rating scale.
- The AI skill group will be placed at display order position 1 (first in the grid), with existing groups' display orders incremented by 1 to accommodate.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Visitors can view the Global Head of Engineering position on the employment timeline and access full details within 2 clicks from the employment section
- **SC-002**: The employment timeline displays 10 total entries (up from 9) with the Global position appearing as the most recent current role
- **SC-003**: Visitors can browse 10 skill category groups (up from 9) with the AI group visible and navigable in the skills grid
- **SC-004**: All individual skills within the AI group display with complete information (name, rating, color-coded indicator, description) and are accessible within 2 clicks from the skills page
- **SC-005**: The total number of individual skills increases from 71 by up to 23 new skills (18 in existing groups + 5 AI; exact count depends on deduplication), with zero duplicate skill entries
- **SC-006**: The Global job's Skills tab displays skills from at least 4 different skill groups, demonstrating the cross-category breadth of the role
- **SC-007**: Every skill linked to the Global job shows the Global position in its job correlations view, maintaining 100% bidirectional consistency
- **SC-008**: The PDF resume includes the Global Head of Engineering position with accurate dates, description, and associated skills when downloaded
- **SC-009**: All new and updated skill group aggregated ratings accurately reflect their constituent skill ratings with 100% calculation accuracy
