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

     // Deny access to protected applications unless user has DEV_PORTAL_ADMIN role
     const protectedClientIds = [
       event.secrets.LANGFUSE_CLIENT_ID, // Langfuse application
       event.secrets.DEPENDENCY_TRACK_CLIENT_ID, // Dependency-Track application
       event.secrets.TEMPORAL_UI_CLIENT_ID, // Temporal UI application
     ];
     if (protectedClientIds.includes(event.client.client_id)) {
       if (!roles.includes('DEV_PORTAL_ADMIN')) {
         api.access.deny('You do not have permission to access this application.');
       }
     }
   };
   ```

   > **Secrets configuration:** In the Action editor, go to the **Secrets** tab
   > (lock icon) and add:
   >
   > - `LANGFUSE_CLIENT_ID` — Client ID of the Langfuse application created in the
   >   [Langfuse SSO](#langfuse-single-sign-on-sso) section.
   > - `DEPENDENCY_TRACK_CLIENT_ID` — Client ID of the Dependency-Track application
   >   created in the [Dependency-Track SSO](#dependency-track-single-sign-on-sso) section.
   > - `TEMPORAL_UI_CLIENT_ID` — Client ID of the Temporal UI application
   >   created in the [Temporal UI SSO](#temporal-ui-single-sign-on-sso) section.
   >
   > This avoids hardcoding Client IDs in the Action code. A secret referenced in the
   > code but not defined here evaluates to `undefined`, which silently drops that
   > application out of `protectedClientIds` — so the deny check stops applying to it.

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
Access is restricted to users with the `DEV_PORTAL_ADMIN` role (enforced by
the Post-Login Action in [step 5c](#5c-add-a-post-login-action-that-injects-roles-into-the-tokens)).

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
9. Add the Langfuse Client ID as a secret in the **Add roles to tokens** Action:
   - Go to **Actions** → **Library** → open `Add roles to tokens`
   - Click the **Secrets** tab (lock icon)
   - Add key `LANGFUSE_CLIENT_ID` with the Client ID from step 8
   - Click **Deploy** to save
10. Add these to your `.env` file as:
    - `AUTH_AUTH0_CLIENT_ID`
    - `AUTH_AUTH0_CLIENT_SECRET`
    - `AUTH_AUTH0_ISSUER` (format: `https://YOUR_DOMAIN`)
    - `NEXTAUTH_URL=https://langfuse.simonrowe.dev` (for production environments)

> **Note:** Users without the `DEV_PORTAL_ADMIN` role will see an
> "Access denied" error from Auth0 when attempting to log into Langfuse.
> Assign the role per [step 5b](#5b-assign-the-role-to-your-admin-user).

## Dependency-Track Single Sign-On (SSO)

Dependency-Track uses its own native OIDC support (not a proxy), against this same Auth0
tenant and the same `DEV_PORTAL_ADMIN` role. Both the API server and the SPA are configured
from `.env` — see `docker-compose.prod.yml` and `docs/runbooks/dependency-track.md`.

Every step here is human-gated: none of it can be automated from the repo.

1. In the Auth0 Dashboard, go to **Applications** > **Applications**.
2. Click **Create Application**.
3. Set the name to **Dependency-Track** and select **Single Page Application**.
   (Not "Regular Web Application" — unlike Langfuse, the Dependency-Track SPA runs the
   authorization-code flow in the browser with no client secret.)
4. Click **Create**.
5. Go to the **Settings** tab.
6. In **Allowed Callback URLs**, add the **full callback path**, not just the origin:
   - `https://dependency-track.simonrowe.dev/static/oidc-callback.html`

   > ⚠️ This is the single most common way to get stuck. The SPA redirects to
   > `/static/oidc-callback.html` (a static asset, deliberately routed to the frontend
   > container rather than the API server). Registering only the origin
   > `https://dependency-track.simonrowe.dev` produces an Auth0 callback-mismatch error
   > after an otherwise successful login. The official Dependency-Track docs understate this.

7. In **Allowed Logout URLs**, add:
   - `https://dependency-track.simonrowe.dev`
8. In **Allowed Web Origins**, add:
   - `https://dependency-track.simonrowe.dev`
9. Click **Save Changes** at the bottom.
10. Copy the **Client ID** and **Domain** from the top of the Settings tab. (There is no
    client secret to copy for an SPA, and Dependency-Track does not need one.)
11. Add the Dependency-Track Client ID as a secret in the **Add roles to tokens** Action, so
    users without `DEV_PORTAL_ADMIN` are rejected by Auth0 before reaching Dependency-Track:
    - Go to **Actions** → **Library** → open `Add roles to tokens`
    - Click the **Secrets** tab (lock icon)
    - Add key `DEPENDENCY_TRACK_CLIENT_ID` with the Client ID from step 10
    - Confirm the Action's `protectedClientIds` array includes it, following the existing
      `LANGFUSE_CLIENT_ID` pattern (see
      [step 5c](#5c-add-a-post-login-action-that-injects-roles-into-the-tokens)):

      ```js
      const protectedClientIds = [
        event.secrets.LANGFUSE_CLIENT_ID, // Langfuse application
        event.secrets.DEPENDENCY_TRACK_CLIENT_ID, // Dependency-Track application
      ];
      ```

    - Click **Deploy** to save
12. Add these to your `.env` file (they are consumed by both the `dependencytrack-apiserver`
    and `dependencytrack-frontend` services):
    - `DEPENDENCYTRACK_OIDC_ISSUER` — `https://YOUR_DOMAIN/` — **the trailing slash is
      required.** Dependency-Track does a strict string comparison against the `issuer` field
      of Auth0's discovery document, which always ends in `/`. Without it,
      `/api/v1/oidc/available` returns `false` and the login button silently never renders.
      Confirm the exact value Auth0 serves:

      ```bash
      curl -s "https://YOUR_DOMAIN/.well-known/openid-configuration" | grep -o '"issuer":"[^"]*"'
      ```

    - `DEPENDENCYTRACK_OIDC_CLIENT_ID` — the Client ID from step 10.

    Both are declared in `docker-compose.prod.yml` with compose's required-variable syntax, so
    a missing or empty value fails `docker compose` immediately rather than producing a broken
    deployment. (`DEPENDENCYTRACK_DB_PASSWORD` and `DEPENDENCYTRACK_KEK` are also required, but
    are not Auth0 concerns — see the runbook.)

13. **Create the OIDC group, the team, and the mapping between them.** Log in to
    Dependency-Track with the local break-glass `admin` account.

    > ⚠️ Dependency-Track does **not** match claim values against team names. Creating a team
    > called `DEV_PORTAL_ADMIN` and stopping there produces "Login succeeded, but you don't
    > seem to have any permissions yet" — the exact symptom this step exists to avoid. There
    > are three objects and you need all of them:
    >
    > ```text
    > Auth0 claim value  →  OpenID Connect Group  →  mapping  →  Team  →  permissions
    >    DEV_PORTAL_ADMIN     name must match             you create      name is
    >                         byte-for-byte               this link       arbitrary
    > ```

    a. **Administration → Access Management → OpenID Connect Groups → Create Group.** Name it
       **exactly** `DEV_PORTAL_ADMIN` — uppercase, underscores, no spaces. *This* is the name
       that must equal the `https://simonrowe.dev/roles` claim value byte-for-byte, including
       case. A near-miss (`Dev_Portal_Admin`, `dev_portal_admin`) matches nothing and the user
       lands in no team at all, silently.

    b. **Administration → Access Management → Teams → Create Team.** The team name is
       arbitrary and is *not* matched against anything; `DEV_PORTAL_ADMIN` is a sensible
       choice purely for legibility. Grant it administrative permissions (at minimum
       `SYSTEM_CONFIGURATION`, `ACCESS_MANAGEMENT`, `PORTFOLIO_MANAGEMENT`, `VIEW_PORTFOLIO`,
       `VIEW_VULNERABILITY` — or all permissions, for a single-operator install).

    c. **Map the group to the team** — from either side: the group's **Mapped Teams** menu, or
       the team's **Mapped OpenID Connect Groups** list.

    Note that Dependency-Track never auto-creates OIDC groups from claims it sees, so an empty
    **OpenID Connect Groups** list is *not* evidence that the claim is missing.

    Verify the whole chain landed (run on the Pi, from the deploy directory):

    ```bash
    PW=$(grep '^DEPENDENCYTRACK_DB_PASSWORD=' .env | cut -d= -f2-)
    docker exec -e PGPASSWORD="$PW" simonrowe-dev-monorepo-langfuse-db-1 \
      psql -h 127.0.0.1 -U dtrack -d dtrack -c \
      'SELECT g."NAME" AS oidc_group, t."NAME" AS mapped_team,
              (SELECT count(*) FROM "TEAMS_PERMISSIONS" tp WHERE tp."TEAM_ID"=t."ID") AS perms
       FROM "MAPPEDOIDCGROUP" m
       JOIN "OIDCGROUP" g ON g."ID"=m."GROUP_ID"
       JOIN "TEAM" t ON t."ID"=m."TEAM_ID";'
    ```

    One row with a non-zero `perms` count means the mapping is in place.

14. **Change the local `admin` password immediately.** Dependency-Track seeds `admin`/`admin`
    with a forced password change, and this instance is reachable from the public internet at
    `https://dependency-track.simonrowe.dev`. That account bypasses Auth0 entirely, so until
    the password is changed anyone who finds the hostname is one step from taking it over
    (the forced change means they would also lock you out). Confirm it is no longer default:

    ```bash
    curl -s -o /dev/null -w '%{http_code}\n' -X POST \
      -H 'Content-Type: application/x-www-form-urlencoded' \
      --data-urlencode 'username=admin' --data-urlencode 'password=admin' \
      https://dependency-track.simonrowe.dev/api/v1/user/login
    ```

    `401` with an empty body is correct. `401` with the body `FORCE_PASSWORD_CHANGE` means the
    default password is **still live** — fix it now.

15. **Verify.** Dependency-Track reports whether it considers OIDC usable:

    ```bash
    curl -s https://dependency-track.simonrowe.dev/api/v1/oidc/available
    ```

    Expect `true`. If it returns `false`, the issuer is wrong (step 12) — the SPA hides the
    login button entirely rather than showing an error. Then log in via
    **Login with Auth0** and confirm you land in the portfolio with admin navigation visible.

> ⚠️ **Team synchronisation reconciles on every login.** The API server runs with
> `DT_OIDC_TEAM_SYNCHRONIZATION: "true"`, which means Dependency-Track re-derives each user's
> team membership from the `https://simonrowe.dev/roles` claim **on every single login** — it
> does not merely add teams. If that claim is missing or empty in the ID token (the
> `Add roles to tokens` Action undeployed, dropped from the Login flow, or the user's role
> unassigned), then teams you assigned by hand in the Dependency-Track UI are **stripped** at
> their next login. The symptom is access that worked yesterday being gone today, with
> re-assigning the team "fixing" it only until the next login. Fix the claim, not the team.

> **Note:** Users without the `DEV_PORTAL_ADMIN` role will see an "Access denied" error from
> Auth0 when attempting to log into Dependency-Track, exactly as with Langfuse — provided
> step 11 was completed. The local `admin` account is unaffected and remains the break-glass
> path if OIDC is misconfigured.

## Temporal UI Single Sign-On (SSO)

Temporal Web UI uses native server-side OIDC. It therefore needs a dedicated
Auth0 **Regular Web Application** with a client secret. Do not reuse the public
portfolio SPA client.

1. In **Applications → Applications**, create a **Regular Web Application**
   named `Temporal UI`.
2. Configure:
   - Allowed Callback URLs:
     `https://temporal.simonrowe.dev/auth/sso/callback`
   - Allowed Logout URLs: `https://temporal.simonrowe.dev`
   - Allowed Web Origins: `https://temporal.simonrowe.dev`
3. Copy its Client ID and Client Secret into:
   - `TEMPORAL_AUTH0_CLIENT_ID`
   - `TEMPORAL_AUTH0_CLIENT_SECRET`
4. Set `TEMPORAL_AUTH0_ISSUER` to the Auth0 issuer URL, including its trailing
   slash, for example `https://YOUR_DOMAIN/`.
5. Add an Action secret named `TEMPORAL_UI_CLIENT_ID` with this application's
   Client ID. Confirm `protectedClientIds` contains:

   ```js
   event.secrets.TEMPORAL_UI_CLIENT_ID
   ```

   Deploy the Action and keep it in the Login flow. This is the access-control
   gate: Temporal UI authenticates an OIDC user but does not interpret the
   custom `DEV_PORTAL_ADMIN` claim as application RBAC.
6. Visit `https://temporal.simonrowe.dev`:
   - your `DEV_PORTAL_ADMIN` user should reach the Workflow list;
   - a user without that role must be denied by Auth0;
   - the UI must not offer terminate, cancel, signal, reset, or batch actions
     while `TEMPORAL_DISABLE_WRITE_ACTIONS=true`.

Port `7233` is deliberately bound only to `127.0.0.1` for the host reviewer
worker and remains unreachable through nginx/Pinggy.
