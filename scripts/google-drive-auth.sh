#!/usr/bin/env bash
#
# One-time script to obtain a Google Drive OAuth2 refresh token.
#
# Prerequisites:
#   1. Go to Google Cloud Console > APIs & Services > Credentials
#   2. Create an OAuth 2.0 Client ID (type: Desktop app)
#   3. Note the Client ID and Client Secret
#
# Usage:
#   ./scripts/google-drive-auth.sh <CLIENT_ID> <CLIENT_SECRET>
#
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <CLIENT_ID> <CLIENT_SECRET>"
  exit 1
fi

CLIENT_ID="$1"
CLIENT_SECRET="$2"
SCOPE="https://www.googleapis.com/auth/drive"
REDIRECT_URI="urn:ietf:wg:oauth:2.0:oob"

AUTH_URL="https://accounts.google.com/o/oauth2/v2/auth?client_id=${CLIENT_ID}&redirect_uri=${REDIRECT_URI}&response_type=code&scope=${SCOPE}&access_type=offline&prompt=consent"

echo ""
echo "Open this URL in your browser and authorize the application:"
echo ""
echo "$AUTH_URL"
echo ""
read -rp "Paste the authorization code here: " AUTH_CODE

RESPONSE=$(curl -s -X POST "https://oauth2.googleapis.com/token" \
  -d "code=${AUTH_CODE}" \
  -d "client_id=${CLIENT_ID}" \
  -d "client_secret=${CLIENT_SECRET}" \
  -d "redirect_uri=${REDIRECT_URI}" \
  -d "grant_type=authorization_code")

REFRESH_TOKEN=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('refresh_token',''))" 2>/dev/null)

if [ -z "$REFRESH_TOKEN" ]; then
  echo ""
  echo "ERROR: Failed to obtain refresh token. Response:"
  echo "$RESPONSE"
  exit 1
fi

echo ""
echo "Success! Add these to your environment file:"
echo ""
echo "GOOGLE_DRIVE_CLIENT_ID=${CLIENT_ID}"
echo "GOOGLE_DRIVE_CLIENT_SECRET=${CLIENT_SECRET}"
echo "GOOGLE_DRIVE_REFRESH_TOKEN=${REFRESH_TOKEN}"
echo ""
