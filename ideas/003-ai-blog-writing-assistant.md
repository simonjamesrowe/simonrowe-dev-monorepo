# 003: AI Blog Writing Assistant in Admin Editor

## Summary
Add an AI co-pilot directly into the MDXEditor blog editor in the admin console. Features would include: inline text completion, "write more" continuation, tone/style adjustment, code example generation, and markdown formatting assistance. The assistant would be context-aware, understanding existing blog content and Simon's writing style.

## Why
Writing technical blog posts is time-consuming. An integrated AI assistant that understands the existing content library and writing style would significantly speed up content creation without leaving the editor.

## Features
- **Inline completion** - Tab to accept AI-suggested continuations as you type
- **Selection actions** - Select text and choose: rewrite, simplify, expand, add code example, fix grammar
- **Outline to draft** - Paste bullet points, get a full draft section
- **Code generation** - Describe what code should do, get a formatted code block
- **Style matching** - RAG over existing blog posts to match Simon's writing voice
- **Image suggestions** - Suggest relevant images from the media library based on content

## Technical Approach
- Backend: New `/api/admin/ai/complete` and `/api/admin/ai/transform` endpoints using Spring AI ChatClient
- Use the vector store (from idea 001) to provide writing style context via RAG
- Frontend: MDXEditor plugin or toolbar buttons that call the AI endpoints
- Streaming responses via SSE for real-time text generation in the editor
- Rate limit AI calls to control API costs

## Complexity
Medium. The backend AI integration already exists; this extends it to content creation rather than visitor chat.

## Dependencies
- Spring AI (already in place)
- Optionally depends on idea 001 (vector store) for style-aware RAG
