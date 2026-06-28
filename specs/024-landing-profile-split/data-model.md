# Data Model: Landing Profile Split

## Public Homepage

Represents the root public route.

**Fields/Inputs**

- Profile name
- Profile title
- Profile headline
- Profile background image
- Suggested chat prompts

**Rules**

- Renders only the chat-first hero and then the site footer.
- Does not own profile biography, CV/social links, About content, CTA content, or contact drawer state.
- Keeps existing profile loading and error handling.

## Profile Page

Represents the public `/profile` route.

**Fields/Inputs**

- Profile name
- Profile biography/profile summary
- Profile image
- Social media links
- CV download endpoint
- Contact details/form

**Rules**

- Must expose a `.tour-profile` target.
- Must expose a contact target with `id="contact"` and `.tour-contact`.
- Contact form behavior remains the existing public contact behavior.

## Tour Step

Represents one public guided tour step stored in MongoDB.

**Fields**

- `title`
- `selector`
- `description`
- `titleImage`
- `position`
- `order`
- `createdAt`
- `updatedAt`
- `legacyId`
- `route`

**Rules**

- Seeded defaults must be ordered and deterministic.
- Seeded selectors must exist in the redesigned DOM.
- Seeded defaults must not include removed homepage About or contact drawer selectors.
- Existing admin APIs continue to manage the same entity shape.

## State Transitions

- Homepage route `/` renders the centered hero and footer.
- Profile route `/profile` renders profile content and contact.
- Footer contact route `/profile#contact` loads the Profile page and targets contact content.
- Tour step changes may navigate between `/`, `/profile`, `/experience`, `/blogs`, and `/news-events`.
