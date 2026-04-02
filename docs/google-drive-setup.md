# Google Drive Backup Setup Guide

This guide walks through configuring Google Drive for the backup and restore features in the admin panel.

The application uses **OAuth2 user credentials** (not a service account) so that backups are stored using your personal Google Drive storage quota.

## Prerequisites

- A Google account
- Access to the Google Cloud Console

## Step 1: Create a Google Cloud Project

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Click the project dropdown at the top of the page
3. Click "New Project"
4. Enter a project name (e.g., "simonrowe-backups")
5. Click "Create"
6. Wait for the project to be created, then select it from the project dropdown

## Step 2: Enable the Google Drive API

1. In the Google Cloud Console, go to **APIs & Services > Library**
2. Search for "Google Drive API"
3. Click on "Google Drive API"
4. Click "Enable"

## Step 3: Configure the OAuth Consent Screen

1. Go to **APIs & Services > OAuth consent screen**
2. Select **External** user type (or Internal if using Google Workspace) and click "Create"
3. Fill in the required fields:
   - App name: e.g., "simonrowe-backup"
   - User support email: your email
   - Developer contact email: your email
4. Click "Save and Continue"
5. On the **Scopes** page, click "Add or Remove Scopes"
6. Add the scope `https://www.googleapis.com/auth/drive`
7. Click "Update" then "Save and Continue"
8. On the **Test users** page, click "Add Users" and add your Google account email
9. Click "Save and Continue"

## Step 4: Create OAuth2 Client Credentials

1. Go to **APIs & Services > Credentials**
2. Click "Create Credentials" > "OAuth client ID"
3. Select **Desktop app** as the application type
4. Enter a name (e.g., "simonrowe-backup-client")
5. Click "Create"
6. Note the **Client ID** and **Client Secret** — you'll need both

## Step 5: Obtain a Refresh Token

Run the provided auth script to complete the one-time OAuth2 consent flow:

```bash
./scripts/google-drive-auth.sh <CLIENT_ID> <CLIENT_SECRET>
```

The script will:
1. Print a URL — open it in your browser
2. Sign in with your Google account and authorize the application
3. Copy the authorization code and paste it back into the terminal
4. Output the refresh token

## Step 6: Create a Backup Folder (Optional)

If you want backups stored in a specific folder:

1. Open [Google Drive](https://drive.google.com/) in your browser
2. Create a new folder (e.g., **simonrowe-backups**)
3. Open the folder — the folder ID is the last part of the URL:
   ```
   https://drive.google.com/drive/folders/<FOLDER_ID>
   ```
4. Copy this folder ID

If no folder ID is configured, the application will automatically create a folder named **simonrowe-backups** in your Drive root.

## Step 7: Configure the Environment Variables

| Variable | Required | Description |
|---|---|---|
| `GOOGLE_DRIVE_CLIENT_ID` | Yes | OAuth2 Client ID from Step 4 |
| `GOOGLE_DRIVE_CLIENT_SECRET` | Yes | OAuth2 Client Secret from Step 4 |
| `GOOGLE_DRIVE_REFRESH_TOKEN` | Yes | Refresh token from Step 5 |
| `GOOGLE_DRIVE_FOLDER_ID` | No | Google Drive folder ID from Step 6 |

### For local development

Add to your `backend/.env` file:

```bash
GOOGLE_DRIVE_CLIENT_ID=your-client-id.apps.googleusercontent.com
GOOGLE_DRIVE_CLIENT_SECRET=your-client-secret
GOOGLE_DRIVE_REFRESH_TOKEN=your-refresh-token
GOOGLE_DRIVE_FOLDER_ID=your-folder-id
```

### For Docker Compose

Add to your `docker-compose.yml` or `.env` file:

```yaml
environment:
  GOOGLE_DRIVE_CLIENT_ID: "your-client-id.apps.googleusercontent.com"
  GOOGLE_DRIVE_CLIENT_SECRET: "your-client-secret"
  GOOGLE_DRIVE_REFRESH_TOKEN: "your-refresh-token"
  GOOGLE_DRIVE_FOLDER_ID: "your-folder-id"
```

## Step 8: Verify the Connection

1. Start the backend application
2. Navigate to the admin panel at `/admin/data-operations`
3. The page should show "Google Drive: Connected" with a green indicator
4. If it shows "Not Connected", check the application logs for error details

## How Backups Work

- If `GOOGLE_DRIVE_FOLDER_ID` is set, backups are stored in that folder
- Otherwise, the application creates (or finds) a folder named **"simonrowe-backups"** in your Drive root
- Each backup is a ZIP archive containing:
  - All MongoDB collections as JSON files
  - All uploaded media files
  - A manifest.json with metadata
- Backup file names follow the format: `backup-YYYYMMDD-HHMMSS.zip`
- Backups use your personal Google Drive storage quota

## Troubleshooting

### "Google Drive OAuth2 credentials not configured"

One or more of `GOOGLE_DRIVE_CLIENT_ID`, `GOOGLE_DRIVE_CLIENT_SECRET`, or `GOOGLE_DRIVE_REFRESH_TOKEN` is missing. Verify all three are set in your `.env` file and the application has been restarted.

### "Failed to initialize Google Drive client"

The OAuth2 credentials are invalid. Re-run the auth script to obtain a fresh refresh token.

### "Google Drive connection failed: 403 Forbidden"

The Google Drive API may not be enabled for your project. Go to the Google Cloud Console and verify the Drive API is enabled.

### "invalid_grant" errors

The refresh token has expired or been revoked. This can happen if:
- You revoked access in your Google account settings
- The OAuth consent screen is in "Testing" mode and the token has expired (7-day limit)
- You've created too many refresh tokens

To fix: re-run `./scripts/google-drive-auth.sh` to obtain a new refresh token.

To avoid the 7-day expiry, publish the OAuth consent screen (set status to "In production" in Google Cloud Console — no verification needed for personal use with fewer than 100 users).

### Quota Errors

Google Drive has usage quotas. For a personal backup use case, you are unlikely to hit these limits. If you do, check the [Google Drive API quotas page](https://developers.google.com/drive/api/guides/limits) for details.
