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

## 5. Restrict access with the DEV_PORTAL_ADMIN role

The admin panel is gated by a `DEV_PORTAL_ADMIN` role. Both the backend
(`/api/admin/**`) and the frontend (`/admin` route) require an authenticated
user whose access token / ID token contains that role under the namespaced
claim `https://simonrowe.dev/roles`.

> **Why this is required:** The Google social connection (and any database
> sign-ups) can mint valid JWTs for the API audience. Without RBAC, *any*
> Google user who completes the Auth0 login flow has admin access. Disabling
> database sign-ups alone is not enough.

### 5a. Create the role

1. Navigate to **User Management** → **Roles** → **Create Role**
2. Name: `DEV_PORTAL_ADMIN`
3. Description: `Full access to the simonrowe.dev admin panel`

### 5b. Assign the role to your admin user

1. **User Management** → **Users** → select your admin user
2. **Roles** tab → **Assign Roles** → pick `DEV_PORTAL_ADMIN`

> **Lockout warning:** assign the role to your own user **before** deploying
> the backend role check, otherwise you will lose access to the admin panel.

### 5c. Add a Post-Login Action that injects roles into the tokens

Auth0 does not include role names in tokens by default. Add an Action:

1. **Actions** → **Library** → **Build Custom**
2. Name: `Add roles to tokens`. Trigger: `Login / Post Login`. Runtime: latest Node.
3. Paste:

   ```js
   exports.onExecutePostLogin = async (event, api) => {
     const roles = event.authorization?.roles ?? [];
     api.accessToken.setCustomClaim('https://simonrowe.dev/roles', roles);
     api.idToken.setCustomClaim('https://simonrowe.dev/roles', roles);
   };
   ```

4. Click **Deploy**.
5. **Actions** → **Flows** → **Login** → drag the new Action into the flow → **Apply**.

### 5d. Verify

1. Log out of the admin panel and log in again.
2. Decode the access token at [jwt.io](https://jwt.io) — the payload should contain:

   ```json
   "https://simonrowe.dev/roles": ["DEV_PORTAL_ADMIN"]
   ```

3. Confirm `/admin` loads the dashboard for your user, and that any user *without* the role sees the "Access denied" screen.

### Optional: also disable database sign-ups

If you also want to prevent self-service signup on the database connection
(e.g. so the only admin path is Google + the role above), navigate to
**Authentication** → **Database** → your connection (typically
`Username-Password-Authentication`) and toggle **Disable Sign Ups**. Create
any database admin users manually via **User Management** → **Users** →
**Create User**, then assign them the `DEV_PORTAL_ADMIN` role per 5b.

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

### "Access denied" screen after a successful login

The user authenticated but does not have the `DEV_PORTAL_ADMIN` role.

- Check the user's **Roles** tab in the Auth0 dashboard and assign
  `DEV_PORTAL_ADMIN` if missing.
- Verify the **Add roles to tokens** Action is attached to the **Login** flow
  (Actions → Flows → Login).
- Ask the user to fully sign out (Auth0 logout, not just the local app) and
  sign back in so a fresh token is issued.
- Decode the access token at [jwt.io](https://jwt.io) and confirm the claim
  `https://simonrowe.dev/roles` is present and contains `DEV_PORTAL_ADMIN`.

### 403 Forbidden from `/api/admin/**` despite being signed in

Same root cause as above — the JWT lacks the role claim. See the previous
section.

### CORS errors on API calls

- The backend CORS configuration allows `http://localhost:5173` by default
- For other origins, update the `allowed-origins` in `backend/src/main/resources/application.yml`

## Langfuse Single Sign-On (SSO)

Langfuse requires an Auth0 Application to manage user access via SSO.

1. In the Auth0 Dashboard, go to **Applications** > **Applications**.
2. Click **Create Application**.
3. Set the name to **Langfuse** and select **Regular Web Applications**.
4. Click **Create**.
5. Go to the **Settings** tab.
6. In **Allowed Callback URLs**, add:
   - `http://localhost:3000/api/auth/callback/auth0`
   - `https://langfuse.simonrowe.dev/api/auth/callback/auth0`
7. Click **Save Changes** at the bottom.
8. Copy the **Client ID**, **Client Secret**, and **Domain** from the top of the Settings tab.
9. Add these to your `.env` file as:
   - `AUTH_AUTH0_CLIENT_ID`
   - `AUTH_AUTH0_CLIENT_SECRET`
   - `AUTH_AUTH0_ISSUER_BASE_URL` (format: `https://YOUR_DOMAIN`)
   - `NEXTAUTH_URL=https://langfuse.simonrowe.dev` (for production environments)
