# Data Model: Landing / Profile Cleanup

This feature introduces **no new entities and no schema changes**. It reuses the
existing `Profile` data already exposed by the backend and consumed by the
frontend.

## Entity: Profile (existing, unchanged)

Consumed read-only by the landing hero and profile page.

| Field              | Used by                         | Notes                                  |
|--------------------|---------------------------------|----------------------------------------|
| `name`             | Hero (name), document title     | Split into first/last for accenting    |
| `firstName`        | About heading ("About {first}") | Existing field                         |
| `title`            | Hero role line                  | Shown on mobile and desktop            |
| `headline`         | Hero tagline                    | Hidden on mobile, shown on desktop     |
| `description`      | About body                      | Markdown, real bio text                |
| `profileImage.url` | About photo                     | Real portrait                          |
| `backgroundImage.url` | Hero background photo        | Retained on all viewports              |
| `cvUrl`            | Profile CV download             | Falls back to `/api/resume` if absent  |
| `socialMediaLinks` | Profile social links            | De-duplicated by network type          |

## State / Transitions

None. All views are read-only renders of profile data plus the existing
contact-form submission flow (unchanged).

## Validation Rules

- Inherited from existing components: contact form validation (React Hook Form +
  reCAPTCHA gate) is unchanged.
- CV link must resolve even when `cvUrl` is empty (fallback to default resume
  endpoint) — existing behavior, preserved.
