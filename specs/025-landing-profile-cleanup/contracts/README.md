# Contracts: Landing / Profile Cleanup

**No new or changed API contracts.**

This feature is a frontend presentation change. It consumes existing endpoints
without modification:

- `GET` profile data (existing profile API) — read-only, unchanged.
- CV download endpoint (`profile.cvUrl` or fallback `/api/resume`) — unchanged.
- Contact form submission (existing endpoint behind reCAPTCHA) — unchanged.

No request/response shapes are added or altered, so there are no contract files
to generate for this feature.
