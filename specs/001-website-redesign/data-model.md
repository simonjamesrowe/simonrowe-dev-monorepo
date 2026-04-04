# Data Model: Portfolio Website Redesign

**Feature**: 001-website-redesign
**Date**: 2026-04-03

## Overview

This redesign is primarily a frontend effort. No new backend entities are introduced. Existing MongoDB collections and their schemas remain unchanged. This document maps the existing data model to the new design's UI requirements.

## Existing Entities (Unchanged)

### Profile
- **Collection**: `profile` (singleton document)
- **Used in**: Homepage hero, Profile/Contact page bio, Admin profile management
- **Key fields**: name, title, headline, description, image, cvUrl, socialLinks[]
- **Design mapping**: Hero section title/tagline, Profile page bio text, stats (years experience, scale managed), social links in footer and profile page

### Blog
- **Collection**: `blogs`
- **Used in**: Blog listing page, Homepage (if blog preview retained), Admin blog management
- **Key fields**: id, title, shortDescription, content, featuredImage, tags[] (@DBRef), skills[] (@DBRef), published, createdDate
- **Design mapping**: Blog listing cards (title, shortDescription, featuredImage, createdDate), category filters (derived from tags), featured article (latest published)

### SkillGroup
- **Collection**: `skillGroups`
- **Used in**: Experience/Skills page expertise section
- **Key fields**: id, name, skills[] (@DBRef), order
- **Design mapping**: "Arsenal of an Architect" section — each group renders as a category with its skills as chips

### Skill
- **Collection**: `skills`
- **Used in**: Experience/Skills page (within skill groups), Blog tags
- **Key fields**: id, name, image
- **Design mapping**: Skill chips/pills with name and optional icon

### Job
- **Collection**: `jobs`
- **Used in**: Experience/Skills page timeline
- **Key fields**: id, company, title, startDate, endDate, description, skills[], logoUrl, order
- **Design mapping**: Role timeline cards — company logo, title, duration, description

### Tag
- **Collection**: `tags`
- **Used in**: Blog category filters
- **Key fields**: id, name
- **Design mapping**: Blog listing category filter chips

### ContactRequest (transient — email only)
- **Not persisted** (per constitution Principle V — data only forwarded via email)
- **Used in**: Profile/Contact page form submission
- **Key fields**: firstName, lastName, email, subject, message, recaptchaToken
- **Design mapping**: Contact form on Profile page

## Design-Specific View Models (Frontend Only)

### HomepageStats
- **Source**: Derived from Profile data + hardcoded/configured values
- **Fields**: yearsExperience (string, e.g. "12+"), engineersLed (number), coreStack (string[]), cloudCapabilities (string[])
- **Notes**: These stats appear in the bento grid. They may come from the profile description or be configured as frontend constants until the profile API is extended.

### AdminDashboardMetrics
- **Source**: Partially derived from existing API counts, partially placeholder
- **Fields**: activeVisitors (placeholder), blogReads (placeholder), conversionRate (placeholder), trafficDistribution (placeholder), recentInsights (from data-operations status), topContent (from blog list sorted by engagement — placeholder metric)
- **Notes**: Full analytics requires a future backend integration. The redesign displays these as UI components with graceful empty/placeholder states.

## Entity Relationships (Unchanged)

```
Profile (1) ─── serves ──→ Homepage Hero, Profile Page
Blog (N) ──── @DBRef ──→ Tag (N) [many-to-many]
Blog (N) ──── @DBRef ──→ Skill (N) [many-to-many]
SkillGroup (N) ── @DBRef ──→ Skill (N) [one-to-many]
Job (N) ──── has ──→ Skill references [embedded or @DBRef]
```

## No Schema Migrations Required

This redesign does not modify any backend data models or API contracts. All changes are in the frontend presentation layer.
