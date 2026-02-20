# Requirements Checklist: Skills & Employment

**Feature**: Skills & Employment
**Spec Version**: 2026-02-21
**Status**: Ready for Implementation

---

## User Stories

### P1: Browse Skills by Category

- [x] **US1.1**: Skills page displays grid of 9 skill category groups
- [x] **US1.2**: Each category group shows name, aggregated rating, and representative image
- [x] **US1.3**: Category groups appear in correct display order
- [x] **US1.4**: Clicking a category group opens detailed view
- [x] **US1.5**: Category detail view shows category description
- [x] **US1.6**: Category detail view lists all individual skills within that group
- [x] **US1.7**: Each individual skill displays name, rating, description, and image
- [x] **US1.8**: Skill proficiency ratings use color coding: green (9+), blue (8.5-9), orange (<8.5)
- [x] **US1.9**: Individual skills within category appear in defined display order
- [x] **US1.10**: Visitor can close category detail view and return to grid
- [x] **US1.11**: All 71 skills across 9 groups are accessible and properly displayed

### P2: View Employment Timeline

- [x] **US2.1**: Employment section displays timeline layout
- [x] **US2.2**: Timeline entries alternate between left and right sides
- [x] **US2.3**: Each timeline entry shows company image, job title, and date range
- [x] **US2.4**: Timeline displays all 9 employment entries
- [x] **US2.5**: Clicking a job entry opens detailed view with tabs
- [x] **US2.6**: Detail view provides "About" tab with full description
- [x] **US2.7**: About tab renders markdown content correctly
- [x] **US2.8**: About tab shows company name, location, and clickable website link
- [x] **US2.9**: Detail view provides "Skills" tab showing skills used in position
- [x] **US2.10**: Timeline includes both employment positions and educational experiences
- [x] **US2.11**: Educational experiences are visually distinguished from employment
- [x] **US2.12**: Current positions (no end date) are clearly indicated
- [x] **US2.13**: Only jobs with includeOnResume flag appear in resume-specific contexts

### P3: Explore Skill-Job Correlations

- [x] **US3.1**: Skill detail view displays cards for jobs that used this skill
- [x] **US3.2**: Job cards within skill view show job title, company, and dates
- [x] **US3.3**: Clicking job card from skill view navigates to job details
- [x] **US3.4**: Job Skills tab allows clicking individual skills to view details
- [x] **US3.5**: Clicking skill from job view navigates to skill details
- [x] **US3.6**: Skills used in multiple jobs show all relevant job cards
- [x] **US3.7**: Job cards appear in chronological order within skill views
- [x] **US3.8**: Bidirectional relationships between skills and jobs are consistent

### P4: Download Professional Resume

- [x] **US4.1**: Download resume action is available to visitors
- [x] **US4.2**: Clicking download generates PDF resume
- [x] **US4.3**: PDF downloads to visitor's device
- [x] **US4.4**: PDF displays sidebar with contact information
- [x] **US4.5**: PDF sidebar includes professional links
- [x] **US4.6**: PDF sidebar shows key skills with star ratings or equivalent
- [x] **US4.7**: PDF main area shows employment history in reverse chronological order
- [x] **US4.8**: PDF includes job titles, companies, dates, and descriptions
- [x] **US4.9**: PDF only includes jobs with includeOnResume flag set
- [x] **US4.10**: PDF organizes skills by category group
- [x] **US4.11**: PDF displays visual proficiency indicators for skills
- [x] **US4.12**: PDF places education entries in dedicated education section
- [x] **US4.13**: PDF reflects current data when regenerated
- [x] **US4.14**: PDF renders markdown descriptions appropriately
- [x] **US4.15**: PDF handles current positions (no end date) correctly

---

## Functional Requirements

### Skills Display

- [x] **FR-001**: Display 9 skill category groups in grid layout with name, rating, image, and order
- [x] **FR-002**: Allow selection of category group to view detailed information
- [x] **FR-003**: Display 71 individual skills with name, rating, description, image, and order
- [x] **FR-004**: Apply color-coded indicators: green (≥9), blue (8.5-8.9), orange (<8.5)
- [x] **FR-005**: Aggregate individual skill ratings to calculate category group ratings

### Employment Timeline

- [x] **FR-006**: Display employment entries in alternating left-right timeline layout
- [x] **FR-007**: Display all 9 employment entries including professional and educational
- [x] **FR-008**: Show company image, job title, start date, and end date for each entry
- [x] **FR-009**: Allow selection of job entry to view tabbed detail interface
- [x] **FR-010**: Provide "About" tab with markdown description, company name, location, and website link
- [x] **FR-011**: Provide "Skills" tab showing all skills used in position
- [x] **FR-012**: Distinguish employment positions from educational experiences
- [x] **FR-013**: Respect includeOnResume flag for resume-specific views

### Skill-Job Correlation

- [x] **FR-014**: Display job cards within skill detail view showing which positions used skill
- [x] **FR-015**: Display skill information within job Skills tab
- [x] **FR-016**: Maintain bidirectional navigation between skills and jobs
- [x] **FR-017**: Display job cards in chronological order within skill views
- [x] **FR-018**: Ensure correlation data consistency in both directions

### Resume Generation

- [x] **FR-019**: Generate PDF resume on demand with profile, employment, and skills data
- [x] **FR-020**: Structure PDF with sidebar containing contact info, links, and skills with ratings
- [x] **FR-021**: Structure PDF main area with employment and education in reverse chronological order
- [x] **FR-022**: Include only jobs with includeOnResume flag in PDF employment section
- [x] **FR-023**: Display skills in PDF organized by category with visual proficiency indicators
- [x] **FR-024**: Place education entries in dedicated section separate from employment
- [x] **FR-025**: Render markdown job descriptions appropriately in PDF
- [x] **FR-026**: Handle current positions (no end date) with appropriate indication in PDF

---

## Edge Cases

- [x] **EC-001**: Handle skill category groups containing no individual skills
- [x] **EC-002**: Handle skills with zero associated jobs (no correlation data)
- [x] **EC-003**: Handle jobs with no associated skills in Skills tab
- [x] **EC-004**: Handle jobs with no end date (current positions) in timeline and PDF
- [x] **EC-005**: Handle resume generation when no jobs have includeOnResume flag set
- [x] **EC-006**: Handle very long job descriptions in PDF to prevent page overflow
- [x] **EC-007**: Handle missing or invalid skill ratings for color-coding
- [x] **EC-008**: Handle jobs without company images in timeline display
- [x] **EC-009**: Handle navigation to jobs not marked for resume inclusion from skill views
- [x] **EC-010**: Handle special characters and formatting in markdown descriptions for PDF

---

## Success Criteria

- [x] **SC-001**: All 9 categories and 71 skills accessible within 3 clicks from skills page
- [x] **SC-002**: All 9 employment entries accessible with full details within 2 clicks
- [x] **SC-003**: Bidirectional navigation between any skill and related jobs functions correctly
- [x] **SC-004**: PDF resume generation completes within 5 seconds
- [x] **SC-005**: 100% accuracy in skill proficiency rating color coding
- [x] **SC-006**: 100% accuracy distinguishing employment from educational experiences
- [x] **SC-007**: PDF resume correctly excludes jobs without includeOnResume flag
- [x] **SC-008**: 100% consistency in bidirectional skill-job correlations

---

## Implementation Notes

**Data Relationships**:
- Skill groups contain aggregated ratings from child skills
- Skills maintain references to jobs where they were utilized
- Jobs maintain references to skills used in that position
- Resume generation filters jobs based on includeOnResume flag
- Education flag determines categorization in timeline and PDF

**Key Data Volumes**:
- 9 skill category groups
- 71 individual skills distributed across categories
- 9 employment/education entries
- Bidirectional relationships between skills and jobs

**Visual Design Considerations**:
- Grid layout for skill categories (responsive to screen sizes)
- Alternating timeline layout for employment entries
- Color-coded proficiency indicators (green/blue/orange)
- Tabbed interface for job details
- Sidebar + main content layout for PDF resume

**Quality Assurance Focus Areas**:
- Verify all 71 skills appear in correct categories
- Validate color coding thresholds for ratings
- Test bidirectional navigation completeness
- Confirm PDF includes only resume-flagged jobs
- Validate markdown rendering in both web and PDF
- Test timeline with current positions (null end dates)
