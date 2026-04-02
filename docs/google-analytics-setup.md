# Google Analytics Setup Guide

This guide covers setting up Google Analytics 4 (GA4) for the frontend application. Analytics are used to track page views and homepage interactions.

## Steps

### 1. Create a Google Analytics Account

1. Go to [analytics.google.com](https://analytics.google.com)
2. Sign in with your Google account
3. Click **Start measuring** (or **Admin** → **Create Account** if you already have an account)
4. Enter an account name (e.g. `simonrowe.dev`)
5. Click **Next**

### 2. Create a Property

1. Enter a property name (e.g. `simonrowe.dev`)
2. Set your time zone and currency
3. Click **Next**
4. Fill in business details and click **Create**

### 3. Set Up a Web Data Stream

1. Select **Web** as the platform
2. Enter your website URL (e.g. `https://simonrowe.dev`)
3. Enter a stream name (e.g. `simonrowe.dev - Production`)
4. Click **Create stream**
5. Copy the **Measurement ID** — it starts with `G-` (e.g. `G-XXXXXXXXXX`)

### 4. Set the Environment Variable

Add the Measurement ID to your frontend environment:

```
VITE_GA_MEASUREMENT_ID=G-XXXXXXXXXX
```

Add it to `~/workspace/env` so it's picked up by the Conductor setup script.

When this variable is not set, analytics are silently disabled — no scripts are loaded and no data is sent.

### 5. Verify

1. Start the frontend
2. Open your site and navigate between pages
3. In the Google Analytics console, go to **Reports** → **Realtime** to confirm page views are being received

## What is Tracked

| Event | Description |
|-------|-------------|
| Page views | Tracked on every route change via `trackPageView()` |
| Homepage events | Custom events for homepage interactions via `trackHomepageEvent(action, label)` |

The application uses the `react-ga4` library and initialises analytics on startup only when `VITE_GA_MEASUREMENT_ID` is set.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| No data in Realtime reports | Ensure `VITE_GA_MEASUREMENT_ID` is set and the frontend was restarted after changing the variable |
| Measurement ID not recognised | Check the ID starts with `G-` and matches the Web stream in your GA4 property |
| Data delayed in standard reports | Realtime data appears immediately; standard reports can take 24-48 hours to populate |
| Analytics blocked by ad blockers | This is expected — GA4 scripts are blocked by most ad blockers. Realtime reports will still show unblocked visitors |
