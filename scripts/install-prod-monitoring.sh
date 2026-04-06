#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MONITOR_SCRIPT="$PROJECT_DIR/scripts/monitor-prod.sh"
LOG_DIR="/var/log/prod-health"
LOG_FILE="$LOG_DIR/monitor.log"
LOGROTATE_FILE="/etc/logrotate.d/prod-health"
CRON_LINE="* * * * * $MONITOR_SCRIPT >> $LOG_FILE 2>&1"

if [[ ! -x "$MONITOR_SCRIPT" ]]; then
  echo "ERROR: Monitor script is missing or not executable: $MONITOR_SCRIPT"
  exit 1
fi

echo "Ensuring cron is enabled..."
sudo systemctl enable cron >/dev/null
sudo systemctl start cron

echo "Creating log directory..."
sudo mkdir -p "$LOG_DIR"
sudo touch "$LOG_FILE"
sudo chown "$USER:$USER" "$LOG_DIR" "$LOG_FILE"

echo "Installing logrotate config..."
sudo tee "$LOGROTATE_FILE" >/dev/null <<EOF
$LOG_FILE {
    daily
    rotate 7
    compress
    missingok
    notifempty
    copytruncate
}
EOF

echo "Installing crontab entry..."
EXISTING_CRONTAB="$(crontab -l 2>/dev/null || true)"
FILTERED_CRONTAB="$(printf '%s\n' "$EXISTING_CRONTAB" | grep -Fv "$MONITOR_SCRIPT" || true)"
{
  printf '%s\n' "$FILTERED_CRONTAB" | sed '/^[[:space:]]*$/d'
  printf '%s\n' "$CRON_LINE"
} | crontab -

echo "Installed monitoring job:"
crontab -l | grep -F "$MONITOR_SCRIPT"

echo "Running a verification check..."
"$MONITOR_SCRIPT"

echo "Production monitoring installed."
