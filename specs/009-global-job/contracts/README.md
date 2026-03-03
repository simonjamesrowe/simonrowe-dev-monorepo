# API Contracts: 009-global-job

No new API contracts are required for this feature. The existing APIs defined in spec 004-skills-employment handle all data retrieval:

- `GET /api/jobs` — Returns all jobs (will include the new Global job automatically)
- `GET /api/jobs/{id}` — Returns job detail with resolved skills (will resolve new skill references)
- `GET /api/skills` — Returns all skill groups (will include AI group and new skills)
- `GET /api/skills/{id}` — Returns skill group detail with job correlations (will include Global job)

See `specs/004-skills-employment/contracts/` for the full OpenAPI specifications.
