# 010: AI Image Alt-Text, Captions, and Hero Generation

## Summary
Use AI vision models to automatically generate alt-text and captions for uploaded images. Additionally, integrate an image generation model to create custom hero/banner images for blog posts based on the post content.

## Why
Accessibility requires meaningful alt-text on all images, but writing it manually is often skipped. AI vision models can generate accurate descriptions instantly. Blog hero images currently need to be manually sourced or created - AI generation could produce unique, on-brand visuals for each post.

## Features
- **Auto Alt-Text** - On image upload, AI generates descriptive alt-text
- **Caption Suggestions** - Generate contextual captions for blog images
- **Hero Image Generation** - Generate a blog hero/banner image from the post title and summary
- **Bulk Backfill** - One-click to generate alt-text for all existing images missing descriptions
- **Style Consistency** - Define a visual style prompt template for consistent hero image aesthetics

## Technical Approach
- **Alt-Text**: Use Spring AI with a vision-capable model (GPT-4o, Claude) to describe uploaded images
- **Hero Generation**: Use Spring AI's `ImageModel` abstraction with DALL-E 3 or Stable Diffusion API
- Backend: New endpoints in `AdminMediaController` for AI analysis and generation
- Store generated alt-text in the media document metadata
- Frontend: Show AI suggestions in the media upload flow and image picker

## Complexity
Medium. Vision API calls are straightforward. Image generation quality and style consistency may require prompt engineering iteration.

## Dependencies
- Spring AI with vision model support
- Image generation API (DALL-E 3 or Stability AI)
- Cost considerations: image generation APIs charge per image
