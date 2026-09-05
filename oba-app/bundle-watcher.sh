#!/usr/bin/env bash
set -euo pipefail

BUNDLE_DIR="/bundle"
BACKUP_DIR="/bundle/backup"
REQUEST_FILE="${BUNDLE_DIR}/.rebuild_request.json"
RESULT_FILE="${BUNDLE_DIR}/.rebuild_result.json"
POLL_INTERVAL=5
LAST_NONCE=""

# Source shared library
source /usr/local/bin/bundle-watcher-lib.sh

log "Starting bundle watcher (poll interval: ${POLL_INTERVAL}s)"
while true; do
    if [ -f "$REQUEST_FILE" ]; then
        process_request
    fi
    sleep "$POLL_INTERVAL"
done
