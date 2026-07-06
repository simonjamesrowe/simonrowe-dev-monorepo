# Data Model

No schema changes are required for this feature. The `Blog` entity remains unchanged.

The only changes are data updates performed via Mongock migration to the following fields of existing `Blog` documents:
- `title`
- `shortDescription`
- `featuredImageUrl`
