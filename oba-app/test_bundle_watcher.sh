#!/usr/bin/env bash
# test_bundle_watcher.sh — bash tests for bundle-watcher-lib.sh functions.
# Runs standalone; requires bash and jq.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$SCRIPT_DIR/bundle-watcher-lib.sh"

PASS=0
FAIL=0
FAILURES=()

run_test() {
    local name="$1"
    local tmpdir
    tmpdir="$(mktemp -d)"
    if (
        export BUNDLE_DIR="$tmpdir/bundle"
        export BACKUP_DIR="$tmpdir/bundle/backup"
        export REQUEST_FILE="$tmpdir/bundle/.rebuild_request.json"
        export RESULT_FILE="$tmpdir/bundle/.rebuild_result.json"
        export BUILD_BUNDLE_SH=""  # will be set per-test as needed
        export LAST_NONCE=""
        mkdir -p "$BUNDLE_DIR"
        # shellcheck source=/dev/null
        source "$LIB"
        "$name"
    ); then
        PASS=$((PASS + 1))
        echo "PASS $name"
    else
        FAIL=$((FAIL + 1))
        FAILURES+=("$name")
        echo "FAIL $name"
    fi
    rm -rf "$tmpdir"
}

# ---------------------------------------------------------------------------
# Test: backup_and_restore
# ---------------------------------------------------------------------------
test_backup_and_restore() {
    echo "original data" > "$BUNDLE_DIR/output.obj"
    echo "more data" > "$BUNDLE_DIR/transit.zip"

    backup_bundle

    [ -d "$BACKUP_DIR" ] || { echo "backup dir missing"; return 1; }
    [ -f "$BACKUP_DIR/output.obj" ] || { echo "output.obj not in backup"; return 1; }
    [ -f "$BACKUP_DIR/transit.zip" ] || { echo "transit.zip not in backup"; return 1; }

    # Corrupt bundle
    echo "corrupted" > "$BUNDLE_DIR/output.obj"
    echo "partial" > "$BUNDLE_DIR/partial.tmp"

    restore_bundle

    content="$(cat "$BUNDLE_DIR/output.obj")"
    [ "$content" = "original data" ] || { echo "content not restored: $content"; return 1; }
    [ ! -f "$BUNDLE_DIR/partial.tmp" ] || { echo "partial.tmp should be gone"; return 1; }
    [ ! -d "$BACKUP_DIR" ] || { echo "backup dir should be removed after restore"; return 1; }
}

# ---------------------------------------------------------------------------
# Test: backup_excludes_markers_and_staging
# ---------------------------------------------------------------------------
test_backup_excludes_markers_and_staging() {
    echo "real bundle data" > "$BUNDLE_DIR/bundle.obj"
    echo "request" > "$BUNDLE_DIR/.rebuild_request.json"
    echo "result" > "$BUNDLE_DIR/.rebuild_result.json"
    echo "staging zip" > "$BUNDLE_DIR/gtfs_staging.zip"
    mkdir -p "$BUNDLE_DIR/backup"
    echo "backup data" > "$BUNDLE_DIR/backup/old.obj"

    backup_bundle

    [ -f "$BACKUP_DIR/bundle.obj" ] || { echo "bundle.obj not in backup"; return 1; }
    [ ! -f "$BACKUP_DIR/.rebuild_request.json" ] || { echo ".rebuild_request.json should be excluded"; return 1; }
    [ ! -f "$BACKUP_DIR/.rebuild_result.json" ] || { echo ".rebuild_result.json should be excluded"; return 1; }
    [ ! -f "$BACKUP_DIR/gtfs_staging.zip" ] || { echo "gtfs_staging.zip should be excluded"; return 1; }
    [ ! -d "$BACKUP_DIR/backup" ] || { echo "backup/ subdir should be excluded"; return 1; }
}

# ---------------------------------------------------------------------------
# Test: clean_intermediates removes gtfs-out/ and gtfs_tidied.zip
# ---------------------------------------------------------------------------
test_clean_intermediates() {
    mkdir -p "$BUNDLE_DIR/gtfs-out"
    echo "stop data" > "$BUNDLE_DIR/gtfs-out/stops.txt"
    echo "tidied" > "$BUNDLE_DIR/gtfs_tidied.zip"
    echo "keep me" > "$BUNDLE_DIR/output.obj"

    clean_intermediates

    [ ! -d "$BUNDLE_DIR/gtfs-out" ] || { echo "gtfs-out/ should be removed"; return 1; }
    [ ! -f "$BUNDLE_DIR/gtfs_tidied.zip" ] || { echo "gtfs_tidied.zip should be removed"; return 1; }
    [ -f "$BUNDLE_DIR/output.obj" ] || { echo "output.obj should be untouched"; return 1; }
}

# ---------------------------------------------------------------------------
# Test: clean_intermediates is a no-op when nothing is present
# ---------------------------------------------------------------------------
test_clean_intermediates_noop() {
    clean_intermediates
    return 0
}

# ---------------------------------------------------------------------------
# Test: write_result produces valid JSON with correct types (success=true)
# ---------------------------------------------------------------------------
test_write_result_valid_json() {
    write_result "abc123" "true" "def456def456def456def456def456def456def456def456def456def456def4" ""

    [ -f "$RESULT_FILE" ] || { echo "result file missing"; return 1; }

    local nonce success sha256 error ts
    nonce="$(jq -r '.nonce' "$RESULT_FILE")"
    success="$(jq '.success' "$RESULT_FILE")"
    sha256="$(jq -r '.sha256' "$RESULT_FILE")"
    error="$(jq -r '.error' "$RESULT_FILE")"
    ts="$(jq -r '.ts' "$RESULT_FILE")"

    [ "$nonce" = "abc123" ] || { echo "wrong nonce: $nonce"; return 1; }
    [ "$success" = "true" ] || { echo "success should be boolean true, got: $success"; return 1; }
    [ -n "$sha256" ] || { echo "sha256 missing"; return 1; }
    [ "$error" = "" ] || { echo "error should be empty string, got: $error"; return 1; }
    [ -n "$ts" ] || { echo "ts missing"; return 1; }
}

# ---------------------------------------------------------------------------
# Test: write_result produces correct JSON for failure case
# ---------------------------------------------------------------------------
test_write_result_failure() {
    write_result "abc123" "false" "def456def456def456def456def456def456def456def456def456def456def4" "build_failed_exit_1_bundle_restored"

    local success error
    success="$(jq '.success' "$RESULT_FILE")"
    error="$(jq -r '.error' "$RESULT_FILE")"

    [ "$success" = "false" ] || { echo "success should be boolean false, got: $success"; return 1; }
    [ "$error" = "build_failed_exit_1_bundle_restored" ] || { echo "wrong error: $error"; return 1; }
}

# ---------------------------------------------------------------------------
# Test: process_request with successful build and supervisorctl
# ---------------------------------------------------------------------------
test_process_request_build_success() {
    local stub_dir
    stub_dir="$(mktemp -d)"

    # Write stub build_bundle.sh
    local stub_build="$stub_dir/build_bundle.sh"
    cat > "$stub_build" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
    chmod +x "$stub_build"

    # Write stub supervisorctl
    local stub_supervisorctl="$stub_dir/supervisorctl"
    cat > "$stub_supervisorctl" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
    chmod +x "$stub_supervisorctl"

    export PATH="$stub_dir:$PATH"
    export BUILD_BUNDLE_SH="$stub_build"
    export LAST_NONCE=""

    # Create request file
    local nonce="testnonce1234"
    printf '{"nonce":"%s","sha256":"aaa","staging_filename":"gtfs_staging.zip"}' "$nonce" > "$REQUEST_FILE"

    process_request

    rm -rf "$stub_dir"

    [ -f "$RESULT_FILE" ] || { echo "result file missing"; return 1; }
    local success result_nonce
    success="$(jq '.success' "$RESULT_FILE")"
    result_nonce="$(jq -r '.nonce' "$RESULT_FILE")"
    [ "$success" = "true" ] || { echo "expected success=true, got: $success"; return 1; }
    [ "$result_nonce" = "$nonce" ] || { echo "nonce mismatch: $result_nonce"; return 1; }
}

# ---------------------------------------------------------------------------
# Test: process_request with failed build restores bundle
# ---------------------------------------------------------------------------
test_process_request_build_failure_with_restore() {
    local stub_dir
    stub_dir="$(mktemp -d)"

    # Populate bundle with known content
    echo "good bundle data" > "$BUNDLE_DIR/good.obj"

    # Write stub build_bundle.sh that fails
    local stub_build="$stub_dir/build_bundle.sh"
    cat > "$stub_build" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
    chmod +x "$stub_build"

    export PATH="$stub_dir:$PATH"
    export BUILD_BUNDLE_SH="$stub_build"
    export LAST_NONCE=""

    # Create request file
    local nonce="failnonce1234"
    printf '{"nonce":"%s","sha256":"aaa","staging_filename":"gtfs_staging.zip"}' "$nonce" > "$REQUEST_FILE"

    process_request

    rm -rf "$stub_dir"

    [ -f "$RESULT_FILE" ] || { echo "result file missing"; return 1; }

    local success error
    success="$(jq '.success' "$RESULT_FILE")"
    error="$(jq -r '.error' "$RESULT_FILE")"

    [ "$success" = "false" ] || { echo "expected success=false, got: $success"; return 1; }

    case "$error" in
        *build_failed*) ;;
        *) echo "error should contain build_failed: $error"; return 1 ;;
    esac

    case "$error" in
        *bundle_restored*) ;;
        *) echo "error should contain bundle_restored: $error"; return 1 ;;
    esac

    # Verify bundle was restored
    [ -f "$BUNDLE_DIR/good.obj" ] || { echo "bundle not restored: good.obj missing"; return 1; }
    content="$(cat "$BUNDLE_DIR/good.obj")"
    [ "$content" = "good bundle data" ] || { echo "restored content wrong: $content"; return 1; }
}

# ---------------------------------------------------------------------------
# Test: process_request skips duplicate nonce
# ---------------------------------------------------------------------------
test_process_request_skips_duplicate_nonce() {
    local nonce="dupnonce12345"
    export LAST_NONCE="$nonce"

    printf '{"nonce":"%s","sha256":"aaa","staging_filename":"gtfs_staging.zip"}' "$nonce" > "$REQUEST_FILE"

    process_request

    [ ! -f "$RESULT_FILE" ] || { echo "result file should not be written for duplicate nonce"; return 1; }
}

# ---------------------------------------------------------------------------
# Run all tests
# ---------------------------------------------------------------------------
run_test test_backup_and_restore
run_test test_backup_excludes_markers_and_staging
run_test test_clean_intermediates
run_test test_clean_intermediates_noop
run_test test_write_result_valid_json
run_test test_write_result_failure
run_test test_process_request_build_success
run_test test_process_request_build_failure_with_restore
run_test test_process_request_skips_duplicate_nonce

echo ""
echo "Results: $PASS passed, $FAIL failed"

if [ ${#FAILURES[@]} -gt 0 ]; then
    echo "Failed tests:"
    for f in "${FAILURES[@]}"; do
        echo "  - $f"
    done
    exit 1
fi
exit 0
