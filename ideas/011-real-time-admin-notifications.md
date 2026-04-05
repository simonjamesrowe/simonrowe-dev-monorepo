# 011: Real-Time Admin Notifications

## Summary
Add a notification system to the admin console that provides real-time alerts for important events: new contact form submissions, chat conversations happening live, system health warnings, content publishing reminders, and AI agent task completions.

## Why
Currently, the admin has no way to know in real-time when something happens on the site. Contact form submissions go to email only. Chat conversations are invisible. System issues require checking Grafana. A notification bell in the admin header would centralise awareness.

## Features
- **Notification Bell** - Icon in admin header with unread count badge
- **Notification Types**:
  - New contact form submission
  - AI chat session started (with live message count)
  - System health warning (high error rate, service down)
  - Blog post scheduled for publishing
  - AI agent task completed (if idea 002 is implemented)
  - Backup completed/failed
- **Notification Panel** - Dropdown showing recent notifications with timestamps
- **Desktop Notifications** - Optional browser push notifications via Web Push API
- **Persistence** - Store notifications in MongoDB, mark as read/unread

## Technical Approach
- Backend: `NotificationService` that creates notification documents in MongoDB
- WebSocket (existing STOMP setup) to push notifications to connected admin clients
- Frontend: Notification bell component in admin layout header
- `useNotifications` hook subscribing to WebSocket topic `/topic/admin/notifications`
- Optional: Web Push API for notifications when admin tab is closed

## Complexity
Medium. The WebSocket infrastructure already exists for chat; extending it for admin notifications is relatively straightforward.

## Dependencies
- Existing WebSocket/STOMP infrastructure
- MongoDB for notification persistence
