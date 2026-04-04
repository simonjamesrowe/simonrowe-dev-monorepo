# Feature Specification: Portfolio Website Redesign — "Precision Luminescence"

**Feature Branch**: `001-website-redesign`
**Created**: 2026-04-03
**Status**: Draft
**Input**: User description: "Redesign website using designs in the stitch directory"

## Overview

Redesign the personal portfolio website of Simon Rowe from its current sidebar-navigation, single-page layout into a modern, multi-page editorial experience branded as "The Digital Architect." The redesign introduces a dark, atmospheric visual language ("Precision Luminescence") with glassmorphism effects, asymmetric editorial typography, and an AI-powered chat interface on the homepage. The design covers both the public-facing portfolio and the authenticated admin console, with full desktop and mobile responsive layouts provided.

The stitch directory contains complete design mockups (screenshots + reference HTML) for every page and viewport:

- **Portfolio Home** (desktop + mobile) — Hero with AI chat module, stats bento grid, CTA section
- **Experience & Skills** (desktop + mobile) — Timeline of roles, expertise grid with skill categories
- **Technical Blog** (desktop + mobile) — Featured article hero, category filters, article cards with search
- **Profile & Contact** (desktop + mobile) — Bio section with photo, contact form, social links
- **Admin Dashboard** (desktop + mobile + updated variant) — Analytics overview, profile management, content performance, inquiry list
- **Design System** (cyber_sentinel/DESIGN.md) — Complete color tokens, typography, elevation rules, component specs

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Visitor Browses the Portfolio Homepage (Priority: P1)

A potential employer, recruiter, or fellow engineer visits the website and lands on the homepage. They see a high-impact hero section with Simon's name, professional tagline, and prominent calls-to-action (Download CV, View GitHub). An AI chat module is visible, inviting them to ask questions about Simon's experience. Below the hero, a bento-grid of stats and capability cards communicates expertise at a glance. The page ends with a strong CTA to connect.

**Why this priority**: The homepage is the first impression and the most-visited page. It must immediately communicate professional identity and invite engagement.

**Independent Test**: Can be fully tested by loading the homepage URL and verifying the hero, AI chat module, stats grid, and CTA sections render correctly on desktop and mobile with the new design system.

**Acceptance Scenarios**:

1. **Given** a visitor loads the homepage on desktop, **When** the page renders, **Then** they see a full-width glassmorphic top navigation bar with "The Digital Architect" branding and links to Experience, Skills, Blog, and Admin.
2. **Given** a visitor views the hero section, **When** they look at the right column, **Then** they see the AI chat module with a greeting message, suggested prompt chips, and a text input field.
3. **Given** a visitor scrolls below the hero, **When** the stats section appears, **Then** they see a bento grid with professional summary card, years of experience stat, engineers led stat, core stack card, cloud native card, and a background image card.
4. **Given** a visitor views the homepage on a mobile device, **When** the page renders, **Then** the layout adapts to a single-column stacked layout with the AI chat module below the hero text, and the navigation collapses to a mobile menu.
5. **Given** a visitor clicks "Download CV", **When** the button is activated, **Then** the CV file is downloaded or opened in a new tab.

---

### User Story 2 — Visitor Explores Experience & Skills (Priority: P1)

A visitor navigates to the Experience & Skills page to review Simon's professional history and technical expertise. They see a dramatic headline ("Architecting Digital Fortresses"), a timeline of roles with company logos and descriptions, and an "Arsenal of an Architect" expertise section with categorized skill badges.

**Why this priority**: Experience and skills are the core content that recruiters and employers evaluate. This is a primary conversion driver alongside the homepage.

**Independent Test**: Can be fully tested by navigating to the experience page and verifying the role timeline, skill categories, and responsive layout render correctly.

**Acceptance Scenarios**:

1. **Given** a visitor navigates to the Experience page on desktop, **When** the page renders, **Then** they see a large editorial headline, followed by a two-column layout with role cards showing company logo, title, duration, and description.
2. **Given** a visitor views the Expertise section, **When** the section renders, **Then** they see categorized skill groups displayed as pill-shaped chips with appropriate visual hierarchy differentiating core vs. general skills.
3. **Given** a visitor views the page on mobile, **When** the layout renders, **Then** roles stack vertically in a single column and skills wrap naturally within their categories.

---

### User Story 3 — Visitor Reads the Technical Blog (Priority: P2)

A visitor navigates to the blog section to browse technical articles. They see a featured article prominently displayed at the top, category filter chips (All, Architecture, AI-Native, Engineering Management), a search input, and a grid of article cards with thumbnails, titles, excerpts, and publication dates.

**Why this priority**: The blog establishes thought leadership and drives organic search traffic, but is secondary to the core portfolio content for initial redesign delivery.

**Independent Test**: Can be fully tested by navigating to the blog listing page and verifying the featured article, category filters, search, and article cards render correctly.

**Acceptance Scenarios**:

1. **Given** a visitor navigates to the blog page, **When** the page renders, **Then** they see a "Technical Luminescence" headline, category filter chips, a search input, and a featured article card with image and excerpt.
2. **Given** a visitor clicks a category filter chip, **When** the filter activates, **Then** the article list updates to show only articles matching that category.
3. **Given** a visitor types in the search input, **When** results are filtered, **Then** matching articles appear in real-time.
4. **Given** a visitor clicks "Load Archive" at the bottom, **When** the action completes, **Then** additional older articles are loaded and appended to the listing.
5. **Given** a visitor views the blog on mobile, **When** the layout renders, **Then** articles stack vertically with the featured article displayed prominently at the top, and category chips scroll horizontally.

---

### User Story 4 — Visitor Views Profile & Sends Contact Inquiry (Priority: P2)

A visitor navigates to the Profile & Contact page to learn more about Simon and reach out. They see a professional photo, a detailed bio with key stats (12+ years leadership, 450M+ scale managed), skill tags, social links (LinkedIn, GitHub, Twitter), and a contact form with fields for name, email, subject, and message.

**Why this priority**: Contact is the primary conversion point for business inquiries, but the information also appears partially on the homepage, making this a secondary dedicated page.

**Independent Test**: Can be fully tested by navigating to the profile page, verifying the bio and stats render, and submitting the contact form.

**Acceptance Scenarios**:

1. **Given** a visitor navigates to the Profile page on desktop, **When** the page renders, **Then** they see a two-column layout with a professional photo on the left and bio text, stats, and skill tags on the right.
2. **Given** a visitor fills in all required contact form fields and clicks "Initiate Connection", **When** the form is submitted, **Then** they receive a confirmation that the message was sent successfully.
3. **Given** a visitor submits the contact form with missing required fields, **When** validation runs, **Then** the form highlights the missing fields with appropriate error messages.
4. **Given** a visitor views the page on mobile, **When** the layout renders, **Then** the photo displays above the bio text, and the contact form spans the full width below.

---

### User Story 5 — Admin Views Dashboard & Manages Content (Priority: P3)

An authenticated admin (Simon) logs in and sees the admin dashboard with an overview of site analytics (active visitors, blog reads, conversion rate), traffic distribution chart, recent insights/inquiries, and top-performing content. The admin can manage their profile biography, upload a CV, view and respond to contact inquiries, and quickly create new blog posts.

**Why this priority**: The admin console is used only by the site owner, so while important for content management, it has a smaller user base than the public-facing pages.

**Independent Test**: Can be fully tested by logging in as admin and verifying the dashboard metrics, profile management form, inquiry list, and content cards render correctly.

**Acceptance Scenarios**:

1. **Given** an authenticated admin navigates to the dashboard, **When** the page renders, **Then** they see KPI cards for active visitors, blog reads, and conversion rate, a traffic distribution bar chart with 7-day/30-day toggle, and a "Recent Insights" activity feed.
2. **Given** an admin views the Profile Management section, **When** the section renders, **Then** they can edit their short biography, upload a primary CV and a technical portfolio PDF, and see a preview of their profile image with a recommendation to update.
3. **Given** an admin views the Recent Inquiries section, **When** the section renders, **Then** they see a table of contact form submissions with sender name, subject, type, received date, and status, with action buttons for each.
4. **Given** an admin clicks "+ New Entry", **When** the action is triggered, **Then** they are taken to the content creation flow.
5. **Given** an admin views the dashboard on mobile, **When** the layout renders, **Then** the sidebar collapses, KPI cards stack vertically, and key sections remain accessible via scrolling.

---

### User Story 6 — Design System & Visual Consistency (Priority: P1)

All pages across the site consistently apply the "Precision Luminescence" design system: dark atmospheric palette with teal/cyan primary accents and warm orange secondary accents, Space Grotesk headlines with Inter body text, glassmorphism on navigation and floating elements, no hard borders (background color shifts for separation), and tonal layering for elevation. The design works seamlessly across desktop and mobile breakpoints.

**Why this priority**: Visual consistency is foundational — every other user story depends on the design system being correctly implemented. Without it, no page meets the redesign goal.

**Independent Test**: Can be tested by auditing all pages for consistent color tokens, typography, spacing, and responsive behavior against the design system specification.

**Acceptance Scenarios**:

1. **Given** any page on the site, **When** the page renders, **Then** the background uses the dark surface palette (no pure black), text uses the correct on-surface tokens, and primary (#77d1ff) and secondary (#ffb690) accent colors are applied consistently.
2. **Given** any page on desktop, **When** the top navigation renders, **Then** it uses a glassmorphic style (semi-transparent background with backdrop blur) and shows "The Digital Architect" branding.
3. **Given** any page with cards or sections, **When** they render, **Then** structural separation uses background color shifts (surface to surface-container hierarchy) rather than visible borders or divider lines.
4. **Given** any page at mobile breakpoint, **When** the layout adapts, **Then** content reflows to a single column, navigation becomes a mobile menu, and all interactive elements remain accessible with appropriate touch targets.

---

### Edge Cases

- What happens when the AI chat module fails to connect or the AI service is unavailable? The chat module should display a graceful fallback message and remain non-blocking to the rest of the homepage.
- What happens when a visitor resizes the browser between desktop and mobile breakpoints? The layout should transition smoothly without layout breaks or content overlap.
- What happens when blog content has no featured image? A placeholder or gradient background should be displayed in place of the missing image.
- What happens when the contact form submission fails due to a network error? The form should retain the user's input and display an error message with a retry option.
- What happens when the admin dashboard has no analytics data (new deployment)? Dashboard cards should show zero/empty states with appropriate messaging rather than broken layouts.
- What happens when a visitor navigates directly to a deep URL (e.g., a specific blog post)? The page should render correctly with full navigation context.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Site MUST display a top navigation bar on all public pages with links to Experience, Skills, Blog, and Admin sections
- **FR-002**: Homepage MUST display a hero section with the site owner's name, professional tagline, "Download CV" button, and "View GitHub" link
- **FR-003**: Homepage MUST include an AI chat interface module with a greeting message, suggested prompt chips, and a text input for user queries
- **FR-004**: Homepage MUST display a bento-grid stats section with professional summary, years of experience, engineers led count, core technology stack, and cloud capabilities
- **FR-005**: Experience page MUST display a chronological timeline of professional roles with company information, title, duration, and description
- **FR-006**: Experience page MUST display technical expertise organized into categorized skill groups with differentiated visual treatment for core vs. general skills
- **FR-007**: Blog listing page MUST display a featured article prominently, along with category filter chips, a search input, and a grid of article cards
- **FR-008**: Blog listing page MUST support filtering articles by category and searching by keyword
- **FR-009**: Blog listing page MUST support paginated loading of older articles via a "Load Archive" mechanism
- **FR-010**: Profile page MUST display a professional photo, detailed biography, key career statistics, skill tags, and social media links
- **FR-011**: Profile page MUST include a contact form with fields for name, email, subject, and message, with validation on required fields
- **FR-012**: Admin dashboard MUST display site analytics KPIs (active visitors, blog reads, conversion rate), a traffic distribution chart, and a recent activity feed
- **FR-013**: Admin dashboard MUST include a profile management section for editing biography and uploading CV documents
- **FR-014**: Admin dashboard MUST display a list of contact form inquiries with sender details, subject, status, and action controls
- **FR-015**: All pages MUST apply the "Precision Luminescence" design system: dark atmospheric palette, Space Grotesk headlines, Inter body text, glassmorphic navigation, no hard borders, and tonal layering
- **FR-016**: All pages MUST be fully responsive, adapting from desktop (multi-column) to mobile (single-column) layouts as shown in the design mockups
- **FR-017**: Navigation MUST collapse to a mobile menu on smaller viewports and provide the same access to all sections
- **FR-018**: The site MUST transition from the current sidebar-based single-page layout to a top-navigation multi-page layout as shown in the designs

### Key Entities

- **Profile**: Site owner's identity — name, professional title, biography, photo, career statistics, social links, CV document
- **Blog Post**: Technical article — title, content, featured image, category, tags, publication date, excerpt
- **Skill**: Technical competency — name, category/group, proficiency indicator, core vs. general classification
- **Job/Experience**: Professional role — company name, logo, title, duration, description, key achievements
- **Contact Inquiry**: Visitor message — sender name, email, subject, message body, submission date, status
- **Analytics Summary**: Dashboard metrics — active visitors, blog reads, conversion rate, traffic distribution over time

## Assumptions

- The existing AI chat functionality (profile chat with streaming and MCP tools from feature 009) will be reused and visually restyled to match the new design, not rebuilt from scratch.
- The existing Auth0 authentication system will continue to protect admin routes.
- The existing backend APIs, data models, and content management operations remain unchanged — this redesign is primarily a frontend effort.
- The "Download CV" functionality will link to an uploaded file managed through the existing admin profile system.
- Analytics KPIs on the admin dashboard will be sourced from existing data or represented as visual placeholders until a real analytics integration is implemented.
- The blog category filters map to the existing tag/category system in the backend.
- The current routing structure will be refactored from a sidebar single-page app to a top-nav multi-page app, but URL paths for existing content (e.g., `/blogs/:id`) will remain stable.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All public pages (Home, Experience, Skills, Blog, Profile/Contact) visually match the provided design mockups in the stitch directory for both desktop and mobile viewports
- **SC-002**: Visitors can navigate between all public sections within 1 click from any page via the top navigation bar
- **SC-003**: The homepage loads and renders the hero section, AI chat module, and stats grid within 3 seconds on a standard broadband connection
- **SC-004**: All pages pass a responsive design audit with no layout breaks between 320px and 1920px viewport widths
- **SC-005**: The contact form successfully validates input and submits inquiries, with confirmation displayed to the user within 2 seconds
- **SC-006**: The admin dashboard displays all designed sections (KPIs, traffic chart, profile management, inquiries, top content) behind authentication
- **SC-007**: The design system is consistently applied: all color tokens, typography scales, spacing, and elevation patterns match the DESIGN.md specification across every page
- **SC-008**: 100% of existing functionality (blog browsing, content management, AI chat, authentication) remains operational after the redesign
