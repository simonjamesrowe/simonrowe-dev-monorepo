# API Contracts: Portfolio Website Redesign

**Feature**: 001-website-redesign
**Date**: 2026-04-03

## No New API Contracts

This redesign is a frontend-only effort. All existing backend API endpoints remain unchanged. The frontend will consume the same REST endpoints and WebSocket connections as before.

## Existing Endpoints Consumed by the Redesign

### Public Endpoints (No Auth Required)

| Method | Path | Used By | Notes |
|--------|------|---------|-------|
| GET | `/api/profile` | Homepage hero, Profile page | Profile data, bio, social links |
| GET | `/api/blogs` | Blog listing page | All published blogs |
| GET | `/api/blogs/latest?limit=N` | Homepage blog preview (if retained) | Latest N blogs |
| GET | `/api/blogs/{id}` | Blog detail page | Single blog post |
| GET | `/api/skills` | Experience/Skills page | All skill groups with skills |
| GET | `/api/skills/{id}` | Skill group detail | Single skill group |
| GET | `/api/jobs` | Experience/Skills page | All jobs/experience |
| GET | `/api/jobs/{id}` | Job detail | Single job |
| GET | `/api/resume` | Homepage "Download CV" button | PDF download |
| GET | `/api/search?q=` | Blog search | Global site search |
| GET | `/api/search/blogs?q=` | Blog listing search | Blog-specific search |
| POST | `/api/contact-us` | Profile/Contact page form | Contact form submission |
| WS | `/ws` (STOMP) | Homepage AI chat module | Chat streaming via WebSocket |

### Admin Endpoints (Auth0 JWT Required)

| Method | Path | Used By | Notes |
|--------|------|---------|-------|
| GET | `/api/admin/profile` | Admin dashboard profile section | Admin profile view |
| PUT | `/api/admin/profile` | Admin dashboard profile editor | Update profile |
| GET | `/api/admin/data-operations/status` | Admin dashboard status | Operation status |
| GET | `/api/admin/data-operations/backups` | Admin dashboard | Backup list |
| POST | `/api/admin/media` | Admin media upload | File upload |
| GET | `/api/admin/media` | Admin media library | Paginated media list |

## Frontend Route Contracts (Changed)

| Route | Component | Current | New |
|-------|-----------|---------|-----|
| `/` | HomePage | Single-page with all sections | Hero + AI chat + stats bento grid + CTA |
| `/experience` | ExperiencePage | (section within homepage) | Dedicated page: role timeline + expertise grid |
| `/blogs` | BlogListingPage | Separate page (existing) | Restyled with featured article, filters, search |
| `/blogs/:id` | BlogDetailPage | Separate page (existing) | Restyled to match design system |
| `/profile` | ProfilePage | (section within homepage) | Dedicated page: bio + contact form |
| `/admin/*` | Admin pages | Existing routes | Restyled dashboard, same routes |
