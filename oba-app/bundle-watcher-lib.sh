#!/usr/bin/env bash
# bundle-watcher-lib.sh — shared functions for bundle-watcher and its tests.
# Caller must set: BUNDLE_DIR, BACKUP_DIR, REQUEST_FILE, RESULT_FILE

# Overridable defaults (callers may export these before sourcing)
BUILD_BUNDLE_SH="${BUILD_BUNDLE_SH:-/oba/build_bundle.sh}"
LAST_NONCE="${LAST_NONCE:-}"

log() { echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') bundle-watcher: $*"; }

backup_bundle() {
    if [ -d "$BACKUP_DIR" ]; then
        rm -rf "${BACKUP_DIR:?}/"* 2>/dev/null || true
    else
        mkdir -p "$BACKUP_DIR"
    fi
    local item base
    for item in "$BUNDLE_DIR"/*; do
        [ -e "$item" ] || continue
        base="$(basename "$item")"
        case "$base" in
            .rebuild_request.json|.rebuild_result.json|gtfs_staging.zip|backup) continue ;;
        esac
        cp -a "$item" "$BACKUP_DIR/" || return 1
    done
    return 0
}

restore_bundle() {
    if [ ! -d "$BACKUP_DIR" ] || [ -z "$(ls -A "$BACKUP_DIR" 2>/dev/null)" ]; then
        log "No backup to restore from"
        return 1
    fi
    local item base
    for item in "$BUNDLE_DIR"/*; do
        [ -e "$item" ] || continue
        base="$(basename "$item")"
        case "$base" in
            .rebuild_request.json|.rebuild_result.json|gtfs_staging.zip|backup) continue ;;
        esac
        rm -rf "$item"
    done
    cp -a "$BACKUP_DIR"/* "$BUNDLE_DIR/" || return 1
    rm -rf "$BACKUP_DIR"
    return 0
}

clean_intermediates() {
    [ -d "$BUNDLE_DIR/gtfs-out" ] && rm -rf "$BUNDLE_DIR/gtfs-out" && log "Removed gtfs-out/"
    [ -f "$BUNDLE_DIR/gtfs_tidied.zip" ] && rm -f "$BUNDLE_DIR/gtfs_tidied.zip" && log "Removed gtfs_tidied.zip"
    return 0
}

write_result() {
    local nonce="$1" success="$2" sha256="$3" error="${4:-}"
    jq -n \
        --arg nonce "$nonce" \
        --arg success "$success" \
        --arg sha256 "$sha256" \
        --arg error "$error" \
        --arg ts "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
        '{nonce: $nonce, success: ($success == "true"), sha256: $sha256, error: $error, ts: $ts}' \
        > "$RESULT_FILE"
    log "Wrote result: nonce=$nonce success=$success"
}

process_request() {
    local nonce sha256 staging_filename
    nonce="$(jq -r '.nonce' "$REQUEST_FILE")"
    sha256="$(jq -r '.sha256' "$REQUEST_FILE")"
    staging_filename="$(jq -r '.staging_filename' "$REQUEST_FILE")"

    if [ -z "$nonce" ] || [ "$nonce" = "null" ]; then
        log "Invalid request: missing nonce"
        return
    fi

    if [ "$nonce" = "$LAST_NONCE" ]; then
        return
    fi

    log "Processing rebuild request: nonce=$nonce sha256=$sha256"

    # Step 1: Backup current bundle
    if ! backup_bundle; then
        log "ERROR: Backup failed"
        write_result "$nonce" "false" "$sha256" "backup_failed"
        LAST_NONCE="$nonce"
        return
    fi
    log "Bundle backed up"

    # Step 2: Clean stale intermediates
    clean_intermediates

    # Step 3: Run build_bundle.sh
    log "Running build_bundle.sh with GTFS_ZIP_FILENAME=$staging_filename"
    local build_exit=0
    BUNDLE_DIR="$BUNDLE_DIR" GTFS_ZIP_FILENAME="$staging_filename" "$BUILD_BUNDLE_SH" || build_exit=$?

    if [ "$build_exit" -ne 0 ]; then
        log "ERROR: build_bundle.sh exited with code $build_exit"
        if restore_bundle; then
            log "Bundle restored from backup"
            write_result "$nonce" "false" "$sha256" "build_failed_exit_${build_exit}_bundle_restored"
        else
            log "ERROR: Bundle restore also failed"
            write_result "$nonce" "false" "$sha256" "build_failed_exit_${build_exit}_restore_failed"
        fi
        LAST_NONCE="$nonce"
        return
    fi

    log "Build succeeded"

    # Step 4: Remove backup (no longer needed)
    rm -rf "$BACKUP_DIR"

    # Step 5: Restart tomcat via supervisorctl
    log "Restarting tomcat"
    if supervisorctl -c /etc/supervisor/conf.d/supervisord.conf restart tomcat; then
        log "Tomcat restarted successfully"
        write_result "$nonce" "true" "$sha256" ""
    else
        log "WARNING: Tomcat restart failed"
        write_result "$nonce" "false" "$sha256" "build_succeeded_but_tomcat_restart_failed"
    fi

    LAST_NONCE="$nonce"
}
