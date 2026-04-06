# Data Model: Landing Page AI Redesign

**Date**: 2026-04-06
**Branch**: `015-landing-ai-redesign`

## Overview

This feature introduces no new persistent entities or API changes. All modifications are frontend-only, affecting component state and layout. The data model below documents the new shared state structure.

## ChatContext State Shape

The `ChatContext` consolidates chat-related state currently scattered in `HomePage.tsx` into a shared context provider.

### State Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| chatOpen | boolean | false | Whether the ChatPanel drawer is visible |
| chatQuery | string \| null | null | Initial query to pre-fill when opening chat |
| recaptchaVerified | boolean | false | Whether the user has passed reCAPTCHA in this session |
| showRecaptcha | boolean | false | Whether the RecaptchaGate modal is visible |

### Actions (exposed via context)

| Action | Signature | Description |
|--------|-----------|-------------|
| openChat | (query?: string) => void | Initiates the chat flow: checks reCAPTCHA status, shows gate if needed, or opens chat directly |
| closeChat | () => void | Closes the ChatPanel and clears the query |
| handleRecaptchaVerified | () => void | Called after successful reCAPTCHA verification; marks verified and opens chat |
| cancelRecaptcha | () => void | Called when user closes RecaptchaGate without verifying |

### State Transitions

```
Initial State:
  chatOpen=false, recaptchaVerified=false, showRecaptcha=false, chatQuery=null

User clicks "Ask AI" or hero chat input (not yet verified):
  → showRecaptcha=true, chatQuery="user's question"

User completes reCAPTCHA:
  → recaptchaVerified=true, showRecaptcha=false, chatOpen=true

User clicks "Ask AI" (already verified):
  → chatOpen=true, chatQuery="user's question"

User closes chat:
  → chatOpen=false, chatQuery=null
  (recaptchaVerified remains true for session)
```

## Existing Entities (unchanged)

- **Profile**: Backend entity providing name, title, tagline, CV URL, social links, profile image URL. Consumed by HeroSection via the `useProfile()` hook. No changes needed.
- **Chat Session**: In-memory backend state (ConcurrentHashMap). No changes needed; frontend creates sessions via WebSocket as before.
