# Feature Specification: Content Management System

**Feature Branch**: `007-content-management`
**Created**: 2026-02-21
**Status**: Draft

## Clarifications

### Session 2026-02-21

- Q: What scale are skill proficiency ratings on? (Spec 004 uses 0-10 thresholds, Spec 007 said "1-5 or percentage") → A: 0-10 numeric scale (matches existing data and color thresholds)
- Q: What format is blog content stored/authored in? → A: Markdown — stored as Markdown, rendered to HTML for display. The content editor should support Markdown authoring.
- Q: Should employment descriptions also use Markdown or plain text? → A: Markdown — consistent with blog content, reuses same editor/renderer. Matches existing backup data format.
**Input**: User description: "Content management functionality replacing external CMS dependency. Authenticated administrators can create, edit, and publish website content including blog posts, employment history, skills, profile information, and media assets."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Blog Post Management (Priority: P1)

As a website administrator, I need to create, edit, and publish blog posts with rich text content, images, tags, and skill associations so that I can share technical articles and insights with visitors while maintaining editorial control over what is publicly visible.

**Why this priority**: Blog posts are the primary content type driving visitor engagement and demonstrating technical expertise. This is the core value proposition of the content management system.

**Independent Test**: Can be fully tested by authenticating as an administrator, creating a draft blog post with title, content, and image, then publishing it and verifying the post appears on the public blog page. Delivers immediate value by enabling content publishing.

**Acceptance Scenarios**:

1. **Given** I am an authenticated administrator on the blog post management page, **When** I create a new blog post with a title, rich text content, featured image, tags, and associated skills but leave it as a draft, **Then** the post is saved in the system but does not appear on the public blog listing or detail pages.

2. **Given** I have a draft blog post, **When** I mark it as published and save, **Then** the post immediately appears in the public blog listing ordered by creation date and is accessible via its detail page URL.

3. **Given** I am editing an existing published blog post, **When** I update the content, tags, or skills and save the changes, **Then** the updates are reflected immediately on the public site without creating duplicate entries.

4. **Given** I am creating blog post content, **When** I use the rich text editor to format text (headings, bold, italic, links, lists) and insert images, **Then** the formatted content renders correctly on the public blog post page maintaining all formatting and embedded media.

5. **Given** I am an unauthenticated visitor, **When** I attempt to access the blog post management interface, **Then** I am redirected to the authentication page and cannot view or modify any content.

---

### User Story 2 - Employment History Management (Priority: P2)

As a website administrator, I need to manage employment records including job positions and educational achievements with company details, date ranges, descriptions, and skill associations so that my professional background is accurately represented on the resume and about sections of the website.

**Why this priority**: Professional background is essential for credibility and converting visitors into opportunities. Less frequently updated than blog posts, making it P2.

**Independent Test**: Can be fully tested by authenticating as an administrator, adding a new job entry with company name, dates, description, and skills, then verifying it appears correctly ordered by date on the resume/about page.

**Acceptance Scenarios**:

1. **Given** I am an authenticated administrator on the employment management page, **When** I create a new job entry with company name, title, start date, end date, location, short and long descriptions, company logo, and associated skills, **Then** the entry is saved and appears in the chronological employment list.

2. **Given** I am creating or editing an employment entry, **When** I mark it as an educational achievement (degree, certification) instead of employment, **Then** the system categorizes it appropriately and displays it in the education section rather than work experience.

3. **Given** I have multiple employment entries, **When** I mark specific entries to be included on the resume, **Then** only those marked entries appear in the downloadable/printable resume format while all entries remain visible in the full employment history.

4. **Given** I am entering an ongoing position, **When** I leave the end date empty or mark as "current", **Then** the position displays as "Present" or "Current" on the public-facing pages.

5. **Given** I associate skills with an employment entry, **When** I view the entry on the public site, **Then** the associated skills appear as tags or badges linked to their respective skill detail pages.

---

### User Story 3 - Skills and Competencies Management (Priority: P3)

As a website administrator, I need to manage skills and skill groups with names, proficiency ratings, descriptions, ordering, and visual elements so that visitors can quickly understand my technical capabilities and expertise areas.

**Why this priority**: Skills enhance the profile but are supportive content. They add value but visitors can understand capabilities from blog posts and employment history alone.

**Independent Test**: Can be fully tested by authenticating as an administrator, creating a skill group (e.g., "Backend Development"), adding individual skills to the group with ratings, reordering them, and verifying the skills display in the correct order on the skills page.

**Acceptance Scenarios**:

1. **Given** I am an authenticated administrator on the skills management page, **When** I create a new skill with a name, description, proficiency rating (0-10 numeric scale), and icon/image, **Then** the skill is saved and available for association with blog posts and employment entries.

2. **Given** I am managing skill groups, **When** I create a group (e.g., "Frontend Technologies", "Cloud Infrastructure") and assign multiple skills to it with specific ordering, **Then** the skills appear grouped together on the public skills page in my specified order.

3. **Given** I have multiple skills within a group, **When** I drag-and-drop or use controls to reorder them, **Then** the new order persists and is reflected immediately on the public-facing skills display.

4. **Given** I am editing a skill group, **When** I set a group-level rating or description, **Then** this information displays as a header or summary for that skill category on the public page.

5. **Given** I have created skills, **When** I am writing a blog post or employment entry, **Then** I can select from the existing skills to associate with that content, creating cross-references throughout the site.

---

### User Story 4 - Profile and Media Management (Priority: P4)

As a website administrator, I need to manage my personal profile information, social media links, and upload images for use across the site so that my contact information is current and visual elements are high-quality and properly sized.

**Why this priority**: Profile updates are infrequent and supportive. Media management is a utility feature rather than primary content. Essential infrastructure but lowest content priority.

**Independent Test**: Can be fully tested by authenticating as an administrator, updating profile fields (name, headline, location, contact info), uploading a profile image, and verifying the changes appear on the about/contact pages and the uploaded image is available in multiple sizes.

**Acceptance Scenarios**:

1. **Given** I am an authenticated administrator on the profile management page, **When** I update my name, professional title, headline, bio description, location, phone number, and email addresses, **Then** these changes are saved and reflected across all pages where profile information displays (header, footer, about, contact).

2. **Given** I am managing social media links, **When** I add, edit, or remove links for platforms (LinkedIn, GitHub, Twitter, etc.) and specify which should appear on a resume, **Then** the links display with appropriate icons in the designated areas and resume-marked links appear in the resume export.

3. **Given** I am uploading an image (profile photo, company logo, skill icon, blog post image), **When** the upload completes, **Then** the system automatically generates multiple size variants (thumbnail, small, medium, large) optimized for different display contexts.

4. **Given** I have uploaded images, **When** I am creating or editing content, **Then** I can select from previously uploaded images in a media library rather than re-uploading, and can see thumbnail previews to identify the correct image.

5. **Given** I upload a large image file, **When** the system generates size variants, **Then** the process completes without blocking the user interface, provides progress feedback, and notifies me when all variants are ready.

---

### Edge Cases

- What happens when an administrator attempts to publish a blog post without required fields (title, content)?
- How does the system handle uploading unsupported image file types or excessively large files?
- What occurs if an administrator deletes a skill that is currently associated with blog posts or employment entries?
- How are date ranges validated when end date is before start date in employment entries?
- What happens to published content when an administrator's authentication session expires during editing?
- How does the system prevent data loss if an administrator navigates away from an unsaved form?
- What occurs when multiple size variants of an uploaded image fail to generate due to processing errors?
- How are tags managed when the same tag is spelled differently (case sensitivity, pluralization)?
- What happens when attempting to reorder items in a skill group that contains only one item?
- How does the system handle special characters or very long text in content fields?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST require authentication for all content creation, editing, and deletion operations.
- **FR-002**: System MUST restrict content management access to users explicitly provisioned as administrators through the identity provider.
- **FR-003**: System MUST prevent self-registration and public sign-up for administrative accounts.
- **FR-004**: System MUST provide create, read, update, and delete operations for all content types (blog posts, employment entries, skills, skill groups, profile data, social media links, tags, tour steps).
- **FR-005**: System MUST support draft and published states for blog posts, with drafts visible only to authenticated administrators.
- **FR-006**: System MUST provide a Markdown editor for blog post content supporting formatted text (headings, bold, italic, underline, strikethrough), links, lists (ordered and unordered), and embedded images, with live preview of rendered output.
- **FR-007**: System MUST provide a Markdown editor for employment entry descriptions, consistent with the blog content editor.
- **FR-008**: System MUST support image upload with automatic generation of multiple size variants (thumbnail, small, medium, large) for use in different display contexts.
- **FR-009**: System MUST allow administrators to associate multiple tags with each blog post.
- **FR-010**: System MUST allow administrators to associate multiple skills with each blog post and employment entry.
- **FR-011**: System MUST support ordering of skills within skill groups with persistent sequencing.
- **FR-012**: System MUST support ordering of skill groups with persistent sequencing.
- **FR-013**: System MUST allow administrators to mark employment entries as education versus work experience.
- **FR-014**: System MUST allow administrators to mark employment entries and social media links for inclusion on resume exports.
- **FR-015**: System MUST maintain single profile entity with personal information (name, title, headline, description, location, phone, emails, images).
- **FR-016**: System MUST support ongoing/current employment by allowing empty or current-marked end dates.
- **FR-017**: System MUST provide a media library for viewing and selecting previously uploaded images.
- **FR-018**: System MUST support bulk tag management including creating, renaming, and deleting tags.
- **FR-019**: System MUST provide a data migration mechanism to import existing content from backup sources.
- **FR-020**: System MUST persist all content data durably in the database.
- **FR-021**: System MUST validate required fields before allowing content to be saved as published.
- **FR-022**: System MUST display validation errors clearly when content fails validation.
- **FR-023**: System MUST prevent unauthorized users from accessing content management interfaces.
- **FR-024**: System MUST provide feedback during long-running operations (image processing, bulk imports).
- **FR-025**: System MUST allow deletion of content with confirmation prompts to prevent accidental loss.
- **FR-026**: System MUST support defining tour steps with title, selector, description, title image, position, and order for guided site tours.

### Key Entities

- **Blog Post**: Represents a published or draft article with title, short description, Markdown content, publication state, featured image, creation timestamp, and relationships to tags and skills.
- **Employment Entry**: Represents a job position or educational achievement with title, company/institution name, company URL, date range, location, short and long descriptions, company logo, employment/education categorization, resume inclusion flag, and relationships to skills.
- **Skill**: Represents a technical or professional competency with name, proficiency rating, description, icon/image, display order, and relationships to skill groups, blog posts, and employment entries.
- **Skill Group**: Represents a category of related skills with name, rating, description, image, display order, and relationship to multiple skills.
- **Profile**: Represents the administrator's personal information with name, professional title, headline, bio description, location, phone number, email addresses, and profile images. Single instance per site.
- **Social Media Link**: Represents an external social profile with platform type, URL, display name, and resume inclusion flag.
- **Tag**: Represents a categorical label for blog posts with name and relationships to multiple blog posts.
- **Tour Step**: Represents a single step in a guided site tour with title, UI element selector, description text, title image, position, and sequence order.
- **Media Asset**: Represents an uploaded image with original file and generated size variants (thumbnail, small, medium, large) available for use in content.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Authenticated administrators can create a complete blog post (title, content, image, tags) from blank form to published state in under 3 minutes.
- **SC-002**: All content types (blog posts, employment, skills, skill groups, profile, social media, tags, tour steps) have fully functional create, read, update, and delete operations.
- **SC-003**: Unauthenticated users cannot access any content management interfaces and are redirected to authentication when attempting access.
- **SC-004**: Uploaded images automatically generate all required size variants (thumbnail, small, medium, large) within 10 seconds of upload completion.
- **SC-005**: Existing content from database backup (18 blog posts, 9 employment entries, 71 skills, 9 skill groups, 26 tags, 1 profile, 4 social media links) can be successfully imported via data migration tool.
- **SC-006**: Draft blog posts do not appear in public blog listings or search results, but published posts appear immediately upon publication.
- **SC-007**: Skill ordering changes persist and are reflected on the public skills page within 1 second of saving.
- **SC-008**: Content editors receive clear validation feedback within 2 seconds when attempting to save incomplete or invalid content.
- **SC-009**: Administrators can upload images, select them from media library, and insert them into blog posts within 30 seconds.
- **SC-010**: Profile information updates reflect across all pages (header, footer, about, contact) within 5 seconds of saving changes.
