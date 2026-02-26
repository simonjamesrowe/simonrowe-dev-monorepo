# Auth0 Setup Guide

This guide walks through configuring Auth0 for the content management system admin panel.

## Overview

The CMS uses Auth0 for authentication:

- **Frontend**: Auth0 React SDK (`@auth0/auth0-react`) handles login/logout and token acquisition
- **Backend**: Spring Security OAuth2 Resource Server validates JWT tokens on `/api/admin/**` endpoints

## 1. Create an Auth0 Account

If you don't already have one, sign up at [auth0.com](https://auth0.com). The free tier is sufficient.

## 2. Create a Single Page Application

1. Go to the [Auth0 Dashboard](https://manage.auth0.com/)
2. Navigate to **Applications** > **Create Application**
3. Choose **Single Page Application**
4. Name it `simonrowe-dev-admin`
5. Note the **Domain** and **Client ID** from the application settings

## 3. Configure Application URLs

In the application's **Settings** tab, set:

| Setting | Value |
|---------|-------|
| Allowed Callback URLs | `http://localhost:5173/admin` |
| Allowed Logout URLs | `http://localhost:5173` |
| Allowed Web Origins | `http://localhost:5173` |

For production, add your production domain alongside the localhost entries (comma-separated).

## 4. Create an API

1. Navigate to **Applications** > **APIs** > **Create API**
2. Set:
   - **Name**: `simonrowe-dev-api`
   - **Identifier**: `https://api.simonrowe.dev` (this is the audience URI — it does not need to be a real URL)
3. Note the **Identifier** — this is your `AUTH0_AUDIENCE`

## 5. Disable Self-Registration

Since this is an admin panel, only specific users should have access:

1. Navigate to **Authentication** > **Database** > your connection (usually `Username-Password-Authentication`)
2. Toggle **Disable Sign Ups** to on
3. Create admin users manually via **User Management** > **Users** > **Create User**

## 6. Environment Variables

### Backend

Set these environment variables (or add to `backend/.env`):

```env
AUTH0_DOMAIN=your-tenant.auth0.com
AUTH0_AUDIENCE=https://api.simonrowe.dev
```

These map to the Spring Boot configuration:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://${AUTH0_DOMAIN}/
          audiences: ${AUTH0_AUDIENCE}
```

### Frontend

Create `frontend/.env.local`:

```env
VITE_AUTH0_DOMAIN=your-tenant.auth0.com
VITE_AUTH0_CLIENT_ID=your-client-id-from-step-2
VITE_AUTH0_AUDIENCE=https://api.simonrowe.dev
```

## 7. Verify the Setup

1. Start the backend and frontend (see [README](../README.md#quickstart))
2. Navigate to http://localhost:5173/admin
3. You should be redirected to the Auth0 login page
4. Sign in with the admin user created in step 5
5. After login, you should be redirected back to the admin dashboard
6. API calls from the admin panel should include a valid JWT in the `Authorization` header

## Troubleshooting

### Login redirects back to login (infinite loop)

- Verify **Allowed Callback URLs** matches exactly: `http://localhost:5173/admin`
- Verify `VITE_AUTH0_DOMAIN` and `VITE_AUTH0_CLIENT_ID` are correct and have no trailing spaces
- Open browser dev tools > Application > clear Auth0 cookies and local storage, then retry

### 401 Unauthorized on admin API calls

- Verify `AUTH0_DOMAIN` in the backend matches the **Domain** in Auth0 dashboard
- Verify `AUTH0_AUDIENCE` matches the **Identifier** of the API created in step 4
- Verify the frontend `VITE_AUTH0_AUDIENCE` matches the backend `AUTH0_AUDIENCE`
- Inspect the token at [jwt.io](https://jwt.io) — check the `iss` and `aud` claims match your configuration

### Token has no `aud` claim

- Ensure the API was created in step 4 and its identifier matches `VITE_AUTH0_AUDIENCE`
- The `audience` parameter must be passed in the Auth0Provider's `authorizationParams` — this is already configured in `frontend/src/auth/AuthProvider.tsx`

### CORS errors on API calls

- The backend CORS configuration allows `http://localhost:5173` by default
- For other origins, update the `allowed-origins` in `backend/src/main/resources/application.yml`
