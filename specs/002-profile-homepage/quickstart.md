# Quickstart: Profile & Homepage

**Feature**: 002-profile-homepage
**Date**: 2026-02-21

## Prerequisites

- Docker and Docker Compose installed
- Node.js (latest LTS) for frontend development
- Java 25 JDK for backend development
- MongoDB backup restored (see Migration section below)

## Starting the Application

### 1. Start all services via Docker Compose

From the repository root:

```bash
docker compose up -d
```

This starts:
- **MongoDB** on `localhost:27017`
- **Backend API** on `localhost:8080` (application) and `localhost:8081` (management/actuator)
- **Frontend** on `localhost:3000`

### 2. Start services individually for development

**Backend** (from `backend/` directory):

```bash
./gradlew bootRun
```

The API is available at `http://localhost:8080`.

**Frontend** (from `frontend/` directory):

```bash
npm install
npm run dev
```

The UI is available at `http://localhost:5173` (Vite dev server).

## Verification Checklist

### Step 1: Verify Backend API

Open a terminal and confirm the profile endpoint returns data:

```bash
curl -s http://localhost:8080/api/profile | jq .
```

**Expected**: JSON response containing `name`, `title`, `headline`, `description`, `profileImage`, `sidebarImage`, `backgroundImage`, `mobileBackgroundImage`, `location`, `phoneNumber`, `primaryEmail`, `socialMediaLinks` array.

Verify the health endpoint on the management port:

```bash
curl -s http://localhost:8081/actuator/health | jq .
```

**Expected**: `{"status": "UP"}` with MongoDB connection details.

### Step 2: Verify Homepage Loads

1. Open `http://localhost:3000` (Docker) or `http://localhost:5173` (Vite dev) in a browser.
2. Confirm the page loads without errors.
3. **Loading state**: On initial load, a loading indicator should briefly appear (if data fetch takes more than 100ms).

### Step 3: Verify Profile Display (User Story 1)

On the loaded homepage, verify:

- [ ] **Name and title** are prominently displayed in the hero banner section (e.g., "Mr. Simon Rowe", "Engineering Leader").
- [ ] **Headline message** is visible (e.g., "PASSIONATE ABOUT AI NATIVE DEV...").
- [ ] **Background image** is displayed behind the hero banner section.
- [ ] **Profile photograph** is displayed in the About section.
- [ ] **About section** renders the description text with proper Markdown formatting (bold, italic, links, lists).
- [ ] **Contact details** panel is collapsed by default. Clicking the +/- icon expands/collapses it within 200ms.
- [ ] **Expanded contact details** show: location, primary email (clickable mailto link), phone number.
- [ ] **Download CV button** is visible and links to the resume endpoint.

### Step 4: Verify Sidebar Navigation (User Story 2)

On desktop (viewport > 991px):

- [ ] **Sidebar** is visible on the left side of the page with navigation items: About, Experience, Skills, Blog, Contact.
- [ ] **Sidebar is fixed** -- it remains in position when scrolling down the page.
- [ ] **Navigation icons** are displayed for each section item.
- [ ] **Profile avatar** is shown at the top of the sidebar (using sidebarImage).
- [ ] Clicking a navigation item **smoothly scrolls** to the corresponding section (within 1 second).

Scroll down the page:

- [ ] **Scroll-to-top button** appears after scrolling past 600px.
- [ ] Clicking the scroll-to-top button **smoothly returns** to the top of the page.

### Step 5: Verify Mobile Responsive View (User Story 2)

Resize the browser window to < 991px width (or use browser DevTools mobile simulation at 375px):

- [ ] **Sidebar is hidden** and replaced by a hamburger toggle button.
- [ ] Tapping the **hamburger button** opens the mobile navigation menu (within 300ms transition).
- [ ] The mobile menu displays all navigation items with icons.
- [ ] Tapping a navigation item **closes the menu** and navigates to the correct section.
- [ ] The **background image** switches to the mobile-optimized variant (`mobileBackgroundImage`).
- [ ] All text content is **readable** without horizontal scrolling.
- [ ] Layout adapts properly at **375px** (mobile) and **768px** (tablet) widths.

### Step 6: Verify Social Media Links (User Story 4)

- [ ] **Social media links** are displayed in the sidebar (desktop) and in the profile section.
- [ ] Each link shows an appropriate **platform icon** (GitHub, LinkedIn, Twitter).
- [ ] Clicking a social media link **opens in a new tab/window** (`target="_blank"`).
- [ ] The link navigates to the **correct external profile URL**.

### Step 7: Verify Download CV Button (User Story 3)

- [ ] The **Download CV** button is visible on the homepage (in both the hero banner and the About section).
- [ ] Clicking the button triggers a download or navigates to the resume endpoint.
- [ ] **Note**: Actual PDF generation is owned by Spec 004. At this stage, verify the button exists and links to the correct URL (`/api/resume`). The endpoint may return a 404 until Spec 004 is implemented.

### Step 8: Verify Error Handling (Edge Cases)

Test these scenarios manually or via API manipulation:

- [ ] **Profile data fails to load**: Stop the backend. Refresh the frontend. An error message or fallback content should display instead of a blank page.
- [ ] **Missing images**: If an image URL returns 404, the page should not break. Placeholder handling or graceful hiding should apply.
- [ ] **Empty secondary email**: The contact section should handle empty/missing secondary email without displaying blank content.

## Data Migration

If starting from the existing Strapi MongoDB backup:

```bash
# Restore the backup to local MongoDB
mongorestore --db simonrowe /Users/simonrowe/backups/strapi-backup-20251116_170434/mongodb/strapi/

# Run the migration script (resolves image ObjectId refs, trims fields, etc.)
# Migration script location TBD - will be created as part of implementation tasks
```

## Useful Commands

```bash
# View backend logs
docker compose logs -f backend

# View frontend logs
docker compose logs -f frontend

# Run backend tests
cd backend && ./gradlew test

# Run frontend tests
cd frontend && npm test

# Check API response
curl -s http://localhost:8080/api/profile | jq '.name, .title, .socialMediaLinks[].type'

# Check Prometheus metrics
curl -s http://localhost:8081/actuator/prometheus | grep http_server_requests
```
