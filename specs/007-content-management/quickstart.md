# Quickstart: Content Management System

**Feature**: 007-content-management
**Date**: 2026-02-21

## Prerequisites

- Docker and Docker Compose installed and running
- Node.js 20+ and npm installed
- Java 25 JDK installed
- Auth0 account with a configured application (see Auth0 Setup below)
- Repository cloned: `git clone <repo-url> && cd simonrowe-dev-monorepo`

## Auth0 Setup

Before running the content management system, configure Auth0:

### 1. Create Auth0 Application

1. Log in to [Auth0 Dashboard](https://manage.auth0.com/)
2. Navigate to **Applications** > **Create Application**
3. Select **Single Page Application**
4. Name it `simonrowe-dev-admin`
5. Note the **Domain** and **Client ID**

### 2. Configure Application Settings

In the application settings:

- **Allowed Callback URLs**: `http://localhost:5173/admin/callback`
- **Allowed Logout URLs**: `http://localhost:5173`
- **Allowed Web Origins**: `http://localhost:5173`

### 3. Create Auth0 API

1. Navigate to **APIs** > **Create API**
2. Name: `simonrowe-dev-api`
3. Identifier (Audience): `https://api.simonrowe.dev` (or your chosen audience URI)
4. Note the **Identifier** (this is the `AUTH0_AUDIENCE`)

### 4. Disable Self-Registration

1. Navigate to **Authentication** > **Database** > your connection
2. Disable **Sign Up** toggle
3. Manually provision admin users via **User Management** > **Users**

## Environment Configuration

### Backend Environment Variables

Create or update `backend/.env`:

```env
# Auth0
AUTH0_DOMAIN=your-tenant.auth0.com
AUTH0_AUDIENCE=https://api.simonrowe.dev

# MongoDB (provided by Docker Compose)
SPRING_DATA_MONGODB_URI=mongodb://localhost:27017/simonrowe

# Media storage
MEDIA_DIR=/data/media

# Server
SERVER_PORT=8080
MANAGEMENT_SERVER_PORT=8081
```

### Frontend Environment Variables

Create or update `frontend/.env.local`:

```env
VITE_AUTH0_DOMAIN=your-tenant.auth0.com
VITE_AUTH0_CLIENT_ID=your-client-id
VITE_AUTH0_AUDIENCE=https://api.simonrowe.dev
VITE_API_BASE_URL=http://localhost:8080
```

## Start Development Environment

### 1. Start Infrastructure Services

```bash
docker compose up -d
```

This starts MongoDB, Kafka, and Elasticsearch.

### 2. Start Backend

```bash
cd backend
./gradlew bootRun
```

The backend starts on port 8080 with management on port 8081. Verify:

```bash
curl http://localhost:8081/actuator/health
```

### 3. Start Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on port 5173. Open http://localhost:5173/admin in your browser.

## Data Migration (One-Time)

To import existing content from the Strapi backup:

### 1. Ensure Backup is Accessible

The backup should be at `/Users/simonrowe/backups/strapi-backup-20251116_170434/`. If it is at a different location, set the environment variable:

```bash
export MIGRATION_BACKUP_PATH=/path/to/strapi-backup
```

### 2. Run Migration

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=migrate'
```

The migration service will:
1. Parse BSON files from the backup
2. Create entities in dependency order (tags, skills, skill groups, etc.)
3. Copy media files and generate image variants
4. Log progress for each entity type

Migration is idempotent -- it can be re-run safely. Entities are matched by `legacyId` and upserted.

### 3. Verify Migration

```bash
# Check entity counts
curl -s http://localhost:8080/api/admin/tags \
  -H "Authorization: Bearer $TOKEN" | jq '. | length'
# Expected: 26

curl -s http://localhost:8080/api/admin/blogs \
  -H "Authorization: Bearer $TOKEN" | jq '.totalElements'
# Expected: 18
```

## Admin Dashboard Usage

### Accessing the Admin

1. Navigate to http://localhost:5173/admin
2. You will be redirected to Auth0 login
3. Sign in with your provisioned admin credentials
4. You are redirected back to the admin dashboard

### Creating a Blog Post

1. Navigate to **Blogs** in the admin sidebar
2. Click **New Blog Post**
3. Fill in:
   - **Title**: Your blog post title
   - **Short Description**: Summary for listing cards
   - **Content**: Write in the WYSIWYG Markdown editor
   - **Tags**: Select from existing tags or create new ones
   - **Skills**: Associate relevant skills
   - **Featured Image**: Upload or select from media library
4. Toggle **Published** when ready to make it public
5. Click **Save**

### Uploading Images

1. Navigate to **Media** in the admin sidebar, or use the image button in the Markdown editor
2. Click **Upload** or drag-and-drop an image file
3. Supported formats: JPEG, PNG, GIF, WebP, SVG (max 10 MB)
4. Variants are generated automatically (thumbnail, small, medium, large)
5. Use the media library picker when editing content to select previously uploaded images

### Managing Skills Order

1. Navigate to **Skills** in the admin sidebar
2. Select a skill group
3. Drag-and-drop skills to reorder them within the group
4. Drag-and-drop skill groups to reorder the groups themselves
5. Changes save automatically

## API Quick Reference

All admin endpoints require the `Authorization: Bearer <token>` header.

| Method | Path | Description |
|--------|------|-------------|
| GET/POST | `/api/admin/blogs` | List / create blogs |
| GET/PUT/DELETE | `/api/admin/blogs/{id}` | Get / update / delete blog |
| GET/POST | `/api/admin/jobs` | List / create jobs |
| GET/PUT/DELETE | `/api/admin/jobs/{id}` | Get / update / delete job |
| GET/POST | `/api/admin/skills` | List / create skills |
| GET/PUT/DELETE | `/api/admin/skills/{id}` | Get / update / delete skill |
| PATCH | `/api/admin/skills/reorder` | Reorder skills |
| GET/POST | `/api/admin/skill-groups` | List / create skill groups |
| GET/PUT/DELETE | `/api/admin/skill-groups/{id}` | Get / update / delete skill group |
| PATCH | `/api/admin/skill-groups/reorder` | Reorder skill groups |
| GET/PUT | `/api/admin/profile` | Get / upsert profile |
| GET/POST | `/api/admin/social-media` | List / create social media links |
| PUT/DELETE | `/api/admin/social-media/{id}` | Update / delete social media link |
| GET/POST | `/api/admin/tags` | List / create tags |
| PUT/DELETE | `/api/admin/tags/{id}` | Rename / delete tag |
| POST | `/api/admin/tags/bulk` | Bulk create tags |
| GET/POST | `/api/admin/tour-steps` | List / create tour steps |
| PUT/DELETE | `/api/admin/tour-steps/{id}` | Update / delete tour step |
| PATCH | `/api/admin/tour-steps/reorder` | Reorder tour steps |
| GET/POST | `/api/admin/media` | List / upload media |
| GET/DELETE | `/api/admin/media/{id}` | Get / delete media asset |
| GET | `/media/{id}/{variant}` | Serve image (public, no auth) |

## Running Tests

### Backend Tests

```bash
cd backend
./gradlew test
```

Tests use Testcontainers for MongoDB. Ensure Docker is running. JWT authentication is mocked using Spring Security test utilities.

### Frontend Tests

```bash
cd frontend
npm test
```

Tests use Vitest and React Testing Library. Auth0 is mocked using `@auth0/auth0-react` test utilities.

## Troubleshooting

### Auth0 Login Redirect Loop

- Verify `Allowed Callback URLs` in Auth0 matches exactly: `http://localhost:5173/admin/callback`
- Verify `VITE_AUTH0_DOMAIN` and `VITE_AUTH0_CLIENT_ID` are correct
- Clear browser cookies and local storage

### 401 on Admin API Calls

- Verify `AUTH0_DOMAIN` and `AUTH0_AUDIENCE` match between backend and Auth0 dashboard
- Check that the Auth0 API identifier matches the `audience` in the frontend config
- Inspect the JWT token at [jwt.io](https://jwt.io) to verify claims

### Image Upload Fails

- Verify the `MEDIA_DIR` directory exists and is writable
- Check file size does not exceed 10 MB
- Check file format is one of: JPEG, PNG, GIF, WebP, SVG

### Migration Fails

- Verify BSON files exist at the configured backup path
- Check MongoDB is running and accessible
- Review application logs for specific entity migration errors
