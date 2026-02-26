# Quickstart: 009-global-job

## Prerequisites

- Docker and Docker Compose running
- MongoDB container available (via `docker-compose up -d mongodb`)
- Node.js installed (for running the migration script)

## Steps

### 1. Start Infrastructure

```bash
docker-compose up -d mongodb
```

### 2. Restore Existing Data (if fresh database)

```bash
./scripts/restore-backup.sh
```

### 3. Copy Global Logo

```bash
cp specs/009-global-job/attachments/global-logo.jpg backend/uploads/global-logo.jpg
```

### 4. Run Migration Script

```bash
node scripts/add-global-job-data.js
```

This script:
- Inserts the AI skill group with 5 skills at display order 1
- Shifts existing skill group display orders by +1
- Adds 11 new skills to existing groups (Cloud, CI/CD, Testing, Web, Identity & Security)
- Recalculates affected group ratings
- Inserts the Global Head of Engineering job with ~43 linked skill IDs
- Is idempotent (checks for existing data before inserting)

### 5. Verify

```bash
# Start the full stack
docker-compose up -d

# Check the employment timeline shows 10 entries
curl http://localhost:8080/api/jobs | jq length
# Expected: 10

# Check the skills grid shows 10 groups
curl http://localhost:8080/api/skills | jq length
# Expected: 10

# Verify the Global job exists
curl http://localhost:8080/api/jobs | jq '.[] | select(.company == "Global")'
```

### 6. Create Updated Backup (optional)

```bash
./scripts/create-backup.sh
```

## Key Files

| File | Purpose |
|------|---------|
| `scripts/add-global-job-data.js` | MongoDB migration script (new) |
| `backend/uploads/global-logo.jpg` | Global company logo (new) |
| `specs/009-global-job/attachments/` | Source attachments (Spotlight reviews, logo) |

## Testing

```bash
# Backend tests
cd backend && ./gradlew test

# Frontend tests
cd frontend && npm test
```

No new test files are expected since this feature adds data only. Existing tests should continue to pass. New integration tests verify the data is correctly inserted and retrievable.
