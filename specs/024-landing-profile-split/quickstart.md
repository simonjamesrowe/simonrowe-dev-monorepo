# Quickstart: Landing Profile Split

## Automated Checks

From the repository root:

```bash
cd frontend && npm test -- HomePage
cd frontend && npm test -- App
cd frontend && npm test -- ProfilePage
cd frontend && npm test -- Tour
cd backend && ./gradlew test --tests '*Tour*'
```

## Manual Browser Verification

1. Start the local environment using the project-standard ports.
2. Open `http://localhost:5173/`.
3. Confirm the top banner spans the full viewport width.
4. Confirm the background photo is visible behind the centered hero.
5. Confirm the homepage hero contains identity text, chat input, and prompt chips.
6. Scroll below the hero and confirm the footer follows without About, CTA, or contact drawer content.
7. Open `http://localhost:5173/profile`.
8. Confirm biography/profile content, CV/social actions, contact details, and contact form appear on the same page.
9. Open `http://localhost:5173/profile#contact`.
10. Confirm the contact section is addressable and visible.
11. Start the guided tour and confirm each seeded step targets an existing element.
