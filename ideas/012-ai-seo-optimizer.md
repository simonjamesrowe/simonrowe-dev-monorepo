# 012: AI-Powered SEO Optimization

## Summary
Add an SEO analysis panel to the blog editor that uses AI to score content against SEO best practices and suggest improvements. Covers keyword density, heading structure, meta tags, readability, internal linking opportunities, and structured data (JSON-LD).

## Why
Blog posts are a key driver of organic traffic. Without SEO tooling, posts may miss easy optimizations. An AI-powered analyser integrated directly into the editor gives real-time feedback during writing, similar to Yoast SEO but powered by LLMs for more nuanced suggestions.

## Features
- **SEO Score** - Overall score (0-100) displayed in editor sidebar
- **Keyword Analysis** - Suggest focus keywords and analyse density
- **Heading Structure** - Validate H1/H2/H3 hierarchy
- **Readability** - Flesch-Kincaid score and plain language suggestions
- **Meta Preview** - Live preview of how the post would appear in Google search results
- **Internal Links** - Suggest links to other blog posts on related topics (uses vector similarity)
- **Structured Data** - Auto-generate JSON-LD BlogPosting schema markup
- **Image SEO** - Check alt-text presence and suggest improvements

## Technical Approach
- Backend: `/api/admin/ai/seo-analyse` endpoint that runs content through LLM with SEO scoring prompt
- Structured output: `{ score, keywords[], headingIssues[], readability, suggestions[] }`
- JSON-LD generation using a template with blog post metadata
- Frontend: Collapsible SEO panel in the blog editor sidebar with colour-coded scores
- Auto-refresh analysis on content change (debounced)

## Complexity
Medium. The LLM-based analysis is straightforward. JSON-LD generation and the Google preview are well-defined frontend tasks.

## Dependencies
- Spring AI (already in place)
- Optionally vector store (idea 001) for internal link suggestions
