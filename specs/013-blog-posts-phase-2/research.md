# Research: Blog Post Series Phase 2

## Decision 1: Content Insertion Method

**Decision**: Use a MongoDB migration script (mongosh `.js` file) with a shell wrapper, following the `update-profile-job-content.js` / `run-content-update.sh` pattern.

**Rationale**: The project already has an established pattern for seeding data via mongosh scripts executed inside the MongoDB container. This approach is:
- Idempotent (checks before inserting)
- Consistent with existing conventions
- Does not require the backend application to be running
- Handles DBRef creation natively in MongoDB shell

**Alternatives considered**:
- Admin CMS API: Would work but requires the backend to be running, Auth0 tokens, and manual image uploads. More steps, less reproducible.
- Direct `mongoimport` with JSON files: Cannot create DBRef references; would need post-processing.

## Decision 2: Featured Image Storage

**Decision**: Generate images externally (ChatGPT/DALL-E), store originals in `specs/013-blog-posts-phase-2/attachments/`, and copy to `backend/uploads/` via the shell wrapper script.

**Rationale**: The blog system serves images from the `/uploads/**` path. The shell wrapper can copy images alongside the migration script execution, ensuring everything is set up in one command.

**Alternatives considered**:
- Upload via Media Library admin API: Requires running backend + Auth0 auth. Less reproducible.
- Store only in uploads/: Would be lost on `git clean` and not tracked in version control.

## Decision 3: Tag Deduplication

**Decision**: The migration script checks for existing tags by name (case-insensitive) before creating new ones. If a tag already exists, it reuses the existing ObjectId.

**Rationale**: Tags have a unique index on `name`. The script must be idempotent — running it twice should not create duplicate tags or fail on unique constraint violations.

**Alternatives considered**:
- Use `updateOne` with `upsert: true`: Would work but doesn't return the `_id` as cleanly for DBRef construction.
- Assume tags don't exist: Would fail on second run due to unique index.

## Decision 4: Blog Post Creation Dates

**Decision**: Stagger creation dates to match the feature merge dates:
- Post 6 (CMS): 2026-03-20 (shortly after feature 007 merge on Mar 17)
- Post 7 (AI Chat): 2026-03-27 (shortly after feature 009 merge on Mar 22)
- Post 8 (Production): 2026-04-05 (shortly after features 011/012 merge on Apr 2-3)

**Rationale**: Staggered dates create a natural series feel in the blog listing and match the chronological order of feature development. Dates are set slightly after merge to reflect "writing about what was just built."

**Alternatives considered**:
- All same date: Loses the series narrative feel.
- Actual current date: All posts would share today's date, looking artificial.

## Decision 5: Skill References

**Decision**: Look up existing skills by name in the migration script. Only reference skills that already exist in the `skills` collection. Do not create new skills.

**Rationale**: The spec states "Skills referenced in blog posts already exist in the system." Creating skills is out of scope for this feature and would require skill group associations.

**Alternatives considered**:
- Create missing skills: Out of scope, would need skill group placement and ratings.
- Skip skills entirely: Would reduce the cross-referencing value of blog posts.
