# Quickstart: Interactive Tour

**Feature**: 008-interactive-tour
**Date**: 2026-02-21
**Phase**: 1 (Design)

## Prerequisites

- Docker and Docker Compose installed
- Java 25 JDK installed (for local backend development)
- Node.js LTS installed (for local frontend development)
- MongoDB running (via Docker Compose or standalone)
- The `001-project-infrastructure` spec must be implemented (provides the Gradle build, Docker Compose, and base project structure)

## 1. Start the Development Environment

From the repository root:

```bash
docker compose up -d
```

This starts MongoDB, Elasticsearch, Kafka, the backend, and the frontend as defined in the infrastructure spec. The backend API is available at `http://localhost:8080` and the frontend at `http://localhost:5173`.

## 2. Seed Tour Step Data

Insert sample tour steps into MongoDB. Connect to the local MongoDB instance and run:

```bash
docker compose exec mongodb mongosh --eval '
db.tourSteps.insertMany([
  {
    order: 1,
    targetSelector: ".homepage-banner",
    title: "Welcome to My Site",
    titleImage: null,
    description: "This is the **homepage banner**. Here you can see my name, professional title, and a brief introduction.\n\nClick *Next* to explore the site.",
    position: "bottom"
  },
  {
    order: 2,
    targetSelector: "#about-section",
    title: "About Me",
    titleImage: null,
    description: "Learn about my **professional background**, experience, and interests.\n\n- Over 10 years of software engineering experience\n- Passionate about cloud-native technologies\n- Based in the UK",
    position: "top"
  },
  {
    order: 3,
    targetSelector: ".tour-search",
    title: "Site Search",
    titleImage: null,
    description: "Use the **search bar** to find content across the entire site.\n\nWatch as we demonstrate how search works -- results update as you type!",
    position: "bottom"
  },
  {
    order: 4,
    targetSelector: "[data-tour=\"skills\"]",
    title: "Skills & Technologies",
    titleImage: null,
    description: "Browse through my **technical skills** and proficiency levels.\n\n1. Click on any skill to see related blog posts\n2. Skills are grouped by category\n3. Each skill shows a proficiency rating",
    position: "right"
  },
  {
    order: 5,
    targetSelector: "[data-tour=\"blog\"]",
    title: "Blog Posts",
    titleImage: null,
    description: "Read my latest **technical articles** covering topics like:\n\n- Spring Boot and microservices\n- Kubernetes and DevOps\n- Software architecture patterns",
    position: "left"
  },
  {
    order: 6,
    targetSelector: "[data-tour=\"contact\"]",
    title: "Get in Touch",
    titleImage: null,
    description: "Have a question or want to connect? Use the **contact form** to send me a message directly.\n\nThanks for taking the tour!",
    position: "top"
  }
]);
'
```

Verify the data:

```bash
docker compose exec mongodb mongosh --eval 'db.tourSteps.find().sort({order: 1}).pretty()'
```

## 3. Verify the Backend API

Confirm the tour steps endpoint returns data:

```bash
curl -s http://localhost:8080/api/tour/steps | jq .
```

Expected response: a JSON array of 6 tour step objects ordered by the `order` field.

## 4. Verify the Frontend Tour

1. Open `http://localhost:5173` in a desktop browser (viewport width >= 768px)
2. Locate the "Take a Tour" button on the homepage
3. Click the button to start the tour
4. Navigate through steps using "Next" and "Previous" buttons
5. On the search step (step 3), observe the automated search simulation typing queries character-by-character
6. Click "Exit" at any point to close the tour and return to normal browsing

### Mobile Verification

1. Open browser DevTools and toggle device emulation (e.g., iPhone 14, viewport width 390px)
2. Refresh the page
3. Confirm the "Take a Tour" button is not visible

## 5. Run Backend Tests

```bash
./gradlew :backend:test
```

This runs:
- `TourControllerTest` -- WebMvcTest verifying the REST endpoint returns ordered steps, handles empty collections, and returns proper error responses
- `TourStepRepositoryTest` -- Testcontainers integration test verifying MongoDB CRUD operations and order-based sorting

## 6. Run Frontend Tests

```bash
cd frontend && npm test
```

This runs Vitest tests for:
- `TourButton.test.tsx` -- Verifies button visibility on desktop/mobile viewports
- `TourOverlay.test.tsx` -- Verifies overlay rendering and spotlight positioning
- `TourTooltip.test.tsx` -- Verifies tooltip content rendering (title, image, markdown description), navigation controls, and position calculation
- `TourProvider.test.tsx` -- Verifies state transitions (start, next, previous, exit) and context value updates
- `SearchSimulation.test.tsx` -- Verifies character-by-character typing, query progression, and cleanup on abort

## 7. Key Files Reference

### Backend

| File | Purpose |
|------|---------|
| `backend/src/main/java/com/simonrowe/tour/TourStep.java` | MongoDB document record: `id`, `order`, `targetSelector`, `title`, `titleImage`, `description`, `position` |
| `backend/src/main/java/com/simonrowe/tour/TourStepRepository.java` | Spring Data MongoDB repository with `findAllByOrderByOrderAsc()` |
| `backend/src/main/java/com/simonrowe/tour/TourService.java` | Service layer: fetches ordered tour steps from the repository |
| `backend/src/main/java/com/simonrowe/tour/TourController.java` | REST controller: `GET /api/tour/steps` returns `List<TourStep>` |

### Frontend

| File | Purpose |
|------|---------|
| `frontend/src/services/tourApi.ts` | API client: `fetchTourSteps()` calls `GET /api/tour/steps` |
| `frontend/src/components/tour/TourProvider.tsx` | React context provider: manages tour state (active, currentStep, steps), exposes `start()`, `next()`, `prev()`, `exit()` |
| `frontend/src/hooks/useTour.ts` | Custom hook: consumes TourProvider context for use in child components |
| `frontend/src/components/tour/TourButton.tsx` | "Take a Tour" button: visible on desktop (>= 768px), hidden on mobile, calls `start()` |
| `frontend/src/components/tour/TourOverlay.tsx` | Full-screen overlay: dims the page, applies box-shadow spotlight on target element, click-to-dismiss background |
| `frontend/src/components/tour/TourTooltip.tsx` | Positioned tooltip: renders title, optional title image, markdown description via react-markdown, "Previous"/"Next"/"Exit" controls, progress indicator |
| `frontend/src/components/tour/SearchSimulation.tsx` | Search input simulator: types queries character-by-character using AbortController for cancellation |

## 8. Admin Configuration (FR-013)

Tour steps are configured directly in MongoDB. To add, edit, or reorder steps:

**Add a new step**:
```bash
docker compose exec mongodb mongosh --eval '
db.tourSteps.insertOne({
  order: 7,
  targetSelector: "[data-tour=\"new-section\"]",
  title: "New Section",
  titleImage: null,
  description: "Description with **markdown** support.",
  position: "bottom"
});
'
```

**Update an existing step**:
```bash
docker compose exec mongodb mongosh --eval '
db.tourSteps.updateOne(
  { order: 3 },
  { $set: { title: "Updated Title", description: "New **description** text." } }
);
'
```

**Reorder steps** (swap step 4 and step 5):
```bash
docker compose exec mongodb mongosh --eval '
db.tourSteps.updateOne({ order: 4 }, { $set: { order: 99 } });
db.tourSteps.updateOne({ order: 5 }, { $set: { order: 4 } });
db.tourSteps.updateOne({ order: 99 }, { $set: { order: 5 } });
'
```

**Delete a step**:
```bash
docker compose exec mongodb mongosh --eval '
db.tourSteps.deleteOne({ order: 7 });
'
```

Changes take effect on the next tour session (no application restart required). Per SC-007, changes are reflected within 5 seconds for subsequent tour starts.

## 9. Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| "Take a Tour" button not visible | Viewport width below 768px | Widen the browser window or disable device emulation |
| Tour starts but no spotlight appears | `targetSelector` does not match any DOM element | Verify the selector exists on the page using browser DevTools: `document.querySelector('.your-selector')` |
| Search simulation does not run | Current step is not the search step | The simulation only activates when the active step's `targetSelector` is `.tour-search` |
| API returns empty array | No tour step documents in MongoDB | Run the seed data command from step 2 above |
| API returns 500 error | MongoDB connection failure | Verify MongoDB is running: `docker compose ps` and check backend logs: `docker compose logs backend` |
| Tour does not exit on mobile resize | matchMedia listener not firing | This is handled by the TourProvider; verify the component is mounted at the application root |
