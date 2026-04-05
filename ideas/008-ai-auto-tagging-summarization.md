# 008: AI-Powered Auto-Tagging and Summarization

## Summary
Use Spring AI to automatically generate tags, summaries, and meta descriptions for blog posts. When a blog post is saved, the AI analyses the content and suggests relevant tags from existing tags or proposes new ones. It also generates a concise summary for the blog listing page and an SEO-optimized meta description.

## Why
Manual tagging is tedious and inconsistent. AI can analyse the full content and suggest tags that a human might overlook. Auto-generated summaries ensure every blog post has a compelling preview on the listing page without extra effort.

## Features
- **Auto-Tag Suggestions** - On blog save, AI suggests 3-5 tags from the existing tag taxonomy
- **New Tag Proposals** - AI can suggest entirely new tags if content doesn't fit existing ones
- **Summary Generation** - 2-3 sentence summary for the blog listing card
- **Meta Description** - SEO-optimized 155-character meta description
- **One-Click Accept** - Suggestions appear in the editor sidebar; click to accept/reject each

## Technical Approach
- Backend: New `/api/admin/ai/analyse-content` endpoint
- Use Spring AI ChatClient with a structured output parser to get JSON: `{ tags: [], summary: "", metaDescription: "" }`
- Send blog markdown content + list of existing tags as context
- Frontend: "AI Suggest" button in blog editor toolbar, results in a sidebar panel
- Accepted tags/summary are merged into the blog form state

## Complexity
Low-Medium. Straightforward LLM call with structured output. The UI for accepting suggestions is the main frontend work.

## Dependencies
- Spring AI (already in place)
