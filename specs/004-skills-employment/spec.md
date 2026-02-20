# Feature Specification: Skills & Employment

**Feature Branch**: `004-skills-employment`
**Created**: 2026-02-21
**Status**: Draft
**Input**: User description: "This is spec #4 for the simonrowe.dev personal website monorepo rebuild. Covers SKILLS & EMPLOYMENT."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse Skills by Category (Priority: P1)

Visitors explore the site owner's technical skills organized into logical category groups, presented in a scannable visual grid. Each category group displays its name, an aggregated proficiency rating, and a representative image. Upon selecting a category group, visitors see detailed information including the group's description and all individual skills within that category, each with its own name, proficiency rating (visually indicated by color-coded indicators), description, and image.

**Why this priority**: This is the foundation of the skills showcase and the primary way visitors understand the site owner's technical breadth. Without this, the skills section cannot function.

**Independent Test**: Can be fully tested by navigating to the skills page, viewing the grid of 9 skill categories, clicking on any category, and viewing the detailed list of skills within that category. Delivers immediate value by showcasing technical expertise.

**Acceptance Scenarios**:

1. **Given** visitor is on the skills page, **When** the page loads, **Then** a grid displays 9 skill category groups, each showing category name, aggregated rating, and representative image in the correct display order
2. **Given** visitor views the skills grid, **When** they click on a category group (e.g., "Java/Kotlin"), **Then** a detailed view opens showing the category description and all individual skills within that group
3. **Given** visitor views individual skills within a category, **When** reviewing each skill, **Then** each skill displays its name, individual proficiency rating with appropriate color coding (green for 9+, blue for 8.5-9, orange for <8.5), description, and image
4. **Given** visitor has opened a category detail view, **When** they finish reviewing, **Then** they can close the detail view and return to the category grid
5. **Given** the skills data contains 71 total skills across 9 groups, **When** visitor browses all categories, **Then** all skills are accessible and displayed in their defined order

---

### User Story 2 - View Employment Timeline (Priority: P2)

Visitors explore the site owner's professional history through a visual timeline layout where employment entries alternate between left and right sides. Each entry displays the company image, job title, and employment dates. Selecting an entry reveals comprehensive details in a tabbed interface: an "About" tab with the full position description and a "Skills" tab showing all technical skills utilized in that role. Each entry includes a link to the company's website for additional context.

**Why this priority**: Employment history is critical for professional credibility and context, but can function independently of the skills correlation feature. Provides immediate value for visitors evaluating professional background.

**Independent Test**: Can be tested by navigating to the employment section, viewing the timeline of 9 job entries, clicking on any job, and viewing both the "About" and "Skills" tabs with full details. Delivers value as a standalone professional resume.

**Acceptance Scenarios**:

1. **Given** visitor navigates to the employment section, **When** the page loads, **Then** a timeline displays with job entries alternating left and right, each showing company image, job title, and date range
2. **Given** visitor views the timeline, **When** they click on a job entry, **Then** a detailed view opens with two tabs: "About" and "Skills"
3. **Given** visitor is viewing job details, **When** they select the "About" tab, **Then** they see the full position description rendered from markdown, company name, location, and a clickable link to the company website
4. **Given** visitor is viewing job details, **When** they select the "Skills" tab, **Then** they see all technical skills utilized in that position
5. **Given** the employment data includes 9 entries, **When** visitor reviews the timeline, **Then** both employment positions and educational experiences are included, with entries marked accordingly
6. **Given** jobs have different resume inclusion settings, **When** displaying the timeline, **Then** only jobs marked for resume inclusion appear in resume-specific views
7. **Given** visitor views job dates, **When** reviewing active positions, **Then** positions without end dates are clearly indicated as current/ongoing

---

### User Story 3 - Explore Skill-Job Correlations (Priority: P3)

Visitors discover the practical application of skills by seeing which employment positions utilized each specific skill. When viewing an individual skill's details, the interface displays cards representing each job where that skill was used, creating a bidirectional narrative between skills and experience. Similarly, when viewing a job's details, visitors see all skills employed in that role, enriching the understanding of both technical expertise and practical experience.

**Why this priority**: This cross-referencing adds depth to the professional narrative but requires both skills and employment features to be implemented first. It enhances understanding but is not critical for initial value delivery.

**Independent Test**: Can be tested by viewing a skill's detail view and seeing cards for jobs that used it, or by viewing a job's Skills tab and clicking through to individual skill details. Delivers enriched context showing where skills were applied.

**Acceptance Scenarios**:

1. **Given** visitor is viewing an individual skill's details, **When** the detail view loads, **Then** cards display for each job that utilized this skill, showing job title, company, and dates
2. **Given** visitor views skill-to-job correlations, **When** they click on a job card within a skill's detail view, **Then** they navigate to that job's full details
3. **Given** visitor is viewing a job's "Skills" tab, **When** they click on an individual skill, **Then** they navigate to that skill's full details including all other jobs using it
4. **Given** a skill was used in multiple positions, **When** viewing that skill's details, **Then** all relevant job cards appear in chronological order
5. **Given** visitor explores correlations, **When** navigating between skills and jobs, **Then** the bidirectional relationships are consistent and accurate

---

### User Story 4 - Download Professional Resume (Priority: P4)

Visitors download a professionally formatted PDF resume that dynamically compiles the site owner's profile information, employment history, and technical skills into a traditional resume layout. The document features a sidebar containing contact information, professional links, and key skills with visual proficiency ratings, alongside a main content area presenting employment history and educational background in reverse chronological order.

**Why this priority**: Resume generation is valuable for visitors who prefer traditional formats, but is a supplementary feature that depends on all other data being available. It's the final polish rather than core functionality.

**Independent Test**: Can be tested by clicking a "Download Resume" button and receiving a properly formatted PDF containing profile data, employment history with descriptions, and skills with ratings. Delivers value as a portable professional document.

**Acceptance Scenarios**:

1. **Given** visitor wants a traditional resume format, **When** they click the download resume action, **Then** a PDF file is generated and downloaded to their device
2. **Given** the PDF is generated, **When** visitor opens it, **Then** it displays a sidebar layout containing contact information, professional links, and key skills with star ratings
3. **Given** the PDF main content area, **When** reviewing employment section, **Then** only jobs marked with includeOnResume flag appear, in reverse chronological order with titles, companies, dates, and descriptions
4. **Given** the PDF includes skills, **When** viewing the skills section, **Then** skills are organized by category group with visual proficiency indicators
5. **Given** the PDF includes educational background, **When** reviewing the document, **Then** entries marked as education appear in a dedicated education section
6. **Given** the source data is updated, **When** a new resume is generated, **Then** it reflects the current profile, employment, and skills data

---

### Edge Cases

- What happens when a skill category group contains no individual skills?
- What happens when a skill has been assigned to zero jobs (no correlation data)?
- What happens when a job entry has no associated skills in the Skills tab?
- How does the system handle jobs with no end date (current positions) in the timeline and PDF resume?
- What happens when resume generation is requested but no jobs have the includeOnResume flag set?
- How does the system handle very long job descriptions in the PDF layout to prevent page overflow?
- What happens when skill ratings are missing or invalid for color-coding?
- How does the system display jobs without company images in the timeline?
- What happens when a visitor tries to navigate to a job from a skill detail view, but that job is not marked for resume inclusion?
- How does the PDF handle special characters or formatting in markdown descriptions?

## Requirements *(mandatory)*

### Functional Requirements

#### Skills Display Requirements

- **FR-001**: System MUST display 9 skill category groups in a grid layout, each showing category name, aggregated proficiency rating, representative image, and maintaining defined display order
- **FR-002**: System MUST allow visitors to select a skill category group and view detailed information including category description and all child skills
- **FR-003**: System MUST display 71 individual skills across all categories, each showing skill name, individual proficiency rating, description, and image in defined display order
- **FR-004**: System MUST apply color-coded visual indicators to skill proficiency ratings: green for ratings 9 and above, blue for ratings 8.5 to 8.9, orange for ratings below 8.5
- **FR-005**: System MUST aggregate individual skill ratings to calculate category group ratings

#### Employment Timeline Requirements

- **FR-006**: System MUST display employment entries in a timeline layout with entries alternating between left and right sides
- **FR-007**: System MUST display 9 employment entries including both professional positions and educational experiences
- **FR-008**: System MUST show for each timeline entry: company image, job title, start date, and end date (or indication of current position)
- **FR-009**: System MUST allow visitors to select a job entry and view detailed information in a tabbed interface
- **FR-010**: System MUST provide an "About" tab showing full position description (rendered from markdown format), company name, location, and clickable link to company website
- **FR-011**: System MUST provide a "Skills" tab showing all technical skills utilized in that position
- **FR-012**: System MUST distinguish between employment positions and educational experiences through appropriate visual indicators or flags
- **FR-013**: System MUST respect the includeOnResume flag to determine which jobs appear in resume-specific views

#### Skill-Job Correlation Requirements

- **FR-014**: System MUST display job cards within each skill's detail view showing which employment positions utilized that skill
- **FR-015**: System MUST display skill information within each job's Skills tab showing which skills were used in that position
- **FR-016**: System MUST maintain bidirectional navigation between skills and jobs, allowing visitors to navigate from skill details to job details and vice versa
- **FR-017**: System MUST display job cards in chronological order when showing which jobs used a particular skill
- **FR-018**: System MUST ensure correlation data is consistent in both directions (if a job lists a skill, that skill must list the job)

#### Resume Generation Requirements

- **FR-019**: System MUST generate a PDF resume document on demand containing dynamically compiled profile, employment, and skills data
- **FR-020**: System MUST structure the PDF with a sidebar layout containing contact information, professional links, and key skills with visual proficiency ratings
- **FR-021**: System MUST structure the PDF main content area with employment history and educational background in reverse chronological order
- **FR-022**: System MUST include only jobs with includeOnResume flag set to true in the PDF employment section
- **FR-023**: System MUST display skills in the PDF organized by category group with visual proficiency indicators (star ratings or equivalent)
- **FR-024**: System MUST place entries marked as education in a dedicated education section separate from employment
- **FR-025**: System MUST render markdown-formatted job descriptions appropriately in the PDF format
- **FR-026**: System MUST handle current positions (no end date) with appropriate indication in the PDF

### Key Entities

- **Skill Group**: Represents a category of related technical skills. Contains: category name, aggregated proficiency rating calculated from child skills, textual description explaining the category, representative image for visual identification, display order for grid layout, and collection of child skills belonging to this category.

- **Skill**: Represents an individual technical competency. Contains: skill name, individual proficiency rating (numeric value), textual description of the skill, representative image, display order within parent category, and relationships to jobs where this skill was utilized.

- **Job**: Represents an employment or educational experience. Contains: job title, company name, company website URL, start date, end date (nullable for current positions), geographic location, short description for timeline display, long description in markdown format for detail view, company logo/image, education flag indicating if this is educational experience, includeOnResume flag controlling PDF inclusion, and relationships to skills utilized in this position.

- **Resume Document**: Represents a dynamically generated PDF document. Compiled from: profile data (contact information, professional links), filtered employment history (only jobs with includeOnResume flag), educational experiences, and skills data organized by category with proficiency ratings. Structured with sidebar layout and main content area.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Visitors can view all 9 skill category groups and navigate to detailed views of all 71 individual skills within 3 clicks from the skills page
- **SC-002**: Visitors can explore the complete employment timeline of 9 entries and access full details for any position within 2 clicks from the employment page
- **SC-003**: Visitors can discover the correlation between any skill and its related jobs, and from any job to its related skills, through direct navigation links
- **SC-004**: Visitors can generate and download a PDF resume containing accurate, current data from profile, employment, and skills within 5 seconds of request
- **SC-005**: All skill proficiency ratings display with correct color coding (green/blue/orange) based on rating thresholds with 100% accuracy
- **SC-006**: The employment timeline correctly distinguishes between professional positions and educational experiences for all 9 entries
- **SC-007**: PDF resume generation excludes jobs without includeOnResume flag set, ensuring only intended positions appear in the downloadable resume
- **SC-008**: Bidirectional skill-job correlations maintain consistency with 100% accuracy (every skill-to-job link has a corresponding job-to-skill link)
