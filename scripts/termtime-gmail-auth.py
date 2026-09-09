#!/usr/bin/env python3
"""One-time script to obtain a Gmail OAuth2 refresh token for Term Time.

Unlike scripts/google-drive-auth.sh, this uses a LOOPBACK redirect rather than
`urn:ietf:wg:oauth:2.0:oob` — Google removed OOB, so the copy-paste-the-code flow
that script uses no longer works for a newly created client.

Two deliberate choices about secret handling:

  * The client id/secret are read from the downloaded client JSON, never passed as
    command-line arguments. Arguments land in shell history and in `ps` output.
  * The refresh token is written to a 0600 file and NEVER printed. Only non-secret
    confirmation goes to stdout, so this is safe to run inside an agent session or
    with someone looking over your shoulder.

The consent screen will show an "unverified app" warning. That is expected: the
project is External/In production but unverified, which Google permits for
personal use (fewer than 100 users). Click through it.

Usage:
    ./scripts/termtime-gmail-auth.py [--client-json PATH] [--out PATH]
"""

import argparse
import http.server
import json
import os
import secrets
import socket
import sys
import threading
import urllib.parse
import urllib.request
import webbrowser

SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
PROFILE_ENDPOINT = "https://gmail.googleapis.com/gmail/v1/users/me/profile"

DEFAULT_CLIENT_JSON = os.path.expanduser(
    "~/workspace/simonjamesrowe/termtime-gmail-oauth-client.json"
)
DEFAULT_OUT = os.path.expanduser(
    "~/workspace/simonjamesrowe/termtime-gmail-token.json"
)


class _CallbackHandler(http.server.BaseHTTPRequestHandler):
    """Captures the single OAuth redirect and then shuts the server down."""

    result = {}

    def do_GET(self):  # noqa: N802 - name fixed by BaseHTTPRequestHandler
        params = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        _CallbackHandler.result = {k: v[0] for k, v in params.items()}
        body = (
            b"<html><body style='font-family:system-ui;padding:3rem'>"
            b"<h2>Term Time &mdash; authorisation received</h2>"
            b"<p>You can close this tab and return to the terminal.</p>"
            b"</body></html>"
        )
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        pass  # keep the console clean


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _post_form(url: str, fields: dict) -> dict:
    data = urllib.parse.urlencode(fields).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def _get_json(url: str, access_token: str) -> dict:
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {access_token}"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--client-json", default=DEFAULT_CLIENT_JSON)
    ap.add_argument("--out", default=DEFAULT_OUT)
    args = ap.parse_args()

    if not os.path.exists(args.client_json):
        print(f"ERROR: client JSON not found: {args.client_json}", file=sys.stderr)
        print("Download it from the OAuth client in Google Cloud Console.", file=sys.stderr)
        return 1

    with open(args.client_json) as fh:
        cfg = json.load(fh)
    node = cfg.get("installed") or cfg.get("web")
    if not node:
        print("ERROR: client JSON has neither an 'installed' nor a 'web' block.", file=sys.stderr)
        return 1
    client_id = node["client_id"]
    client_secret = node["client_secret"]

    port = _free_port()
    redirect_uri = f"http://127.0.0.1:{port}"
    state = secrets.token_urlsafe(24)

    auth_url = AUTH_ENDPOINT + "?" + urllib.parse.urlencode({
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPE,
        # offline + consent together are what actually produce a refresh_token.
        # Without prompt=consent, a re-authorisation of an already-granted client
        # returns an access token only, and the script looks broken.
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    })

    server = http.server.HTTPServer(("127.0.0.1", port), _CallbackHandler)
    threading.Thread(target=server.handle_request, daemon=True).start()

    print("\nOpening the Google consent screen in your browser.")
    print("Expect an 'unverified app' warning — that is normal here; click through it.\n")
    print(f"If the browser does not open, visit:\n\n{auth_url}\n")
    webbrowser.open(auth_url)

    server.socket.settimeout(300)
    for _ in range(300):
        if _CallbackHandler.result:
            break
        threading.Event().wait(1)

    result = _CallbackHandler.result
    if not result:
        print("ERROR: timed out waiting for the redirect.", file=sys.stderr)
        return 1
    if "error" in result:
        print(f"ERROR: consent was refused: {result['error']}", file=sys.stderr)
        return 1
    if result.get("state") != state:
        print("ERROR: state mismatch — refusing to exchange the code.", file=sys.stderr)
        return 1

    tokens = _post_form(TOKEN_ENDPOINT, {
        "code": result["code"],
        "client_id": client_id,
        "client_secret": client_secret,
        "redirect_uri": redirect_uri,
        "grant_type": "authorization_code",
    })

    if "refresh_token" not in tokens:
        print("ERROR: no refresh_token in the response.", file=sys.stderr)
        print("Revoke the app at https://myaccount.google.com/permissions and retry.", file=sys.stderr)
        return 1

    profile = _get_json(PROFILE_ENDPOINT, tokens["access_token"])

    payload = {
        "client_id": client_id,
        "client_secret": client_secret,
        "refresh_token": tokens["refresh_token"],
        "scope": tokens.get("scope"),
        "authorised_email": profile.get("emailAddress"),
    }
    fd = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        json.dump(payload, fh, indent=2)

    print("Success. Nothing secret has been printed.\n")
    print(f"  authorised mailbox : {profile.get('emailAddress')}")
    print(f"  messages in mailbox: {profile.get('messagesTotal')}")
    print(f"  granted scope      : {tokens.get('scope')}")
    print(f"  refresh token      : written to {args.out} (mode 0600)")
    print("\nThe mailbox above is the one Term Time will read. If it is the wrong")
    print("account, revoke at https://myaccount.google.com/permissions and re-run.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
