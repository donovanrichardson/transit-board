#!/bin/bash
# Populates the pass-backed secrets in .env — run this once after cloning, and
# again any time one of the underlying pass entries is rotated.
#
# Why this exists (lifeos-poc#19): these three secrets used to be exported by
# .envrc via direnv. That silently produced blank values in any shell/tool
# invocation where direnv hadn't run (e.g. a bare `docker compose up` from a
# fresh session) — the failure was "app is broken", discovered after the fact,
# not a refusal to start. Writing them into .env instead means `docker compose`
# picks them up natively, with no dependency on the invoking shell's state, and
# docker-compose.yml uses `${VAR:?...}` required-variable syntax so a missing
# value is a hard, immediate error instead of a silent blank.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"

MYSQL_ROOT_PASSWORD=$(pass show transit-board/mysql-root)
JDBC_PASSWORD=$(pass show transit-board/jdbc-password)
OBA_API_KEY=$(pass show transit-board/oba-api-key)

for var in MYSQL_ROOT_PASSWORD JDBC_PASSWORD OBA_API_KEY; do
  value="${!var}"
  if grep -q "^${var}=" "$ENV_FILE" 2>/dev/null; then
    sed -i "s|^${var}=.*|${var}=${value}|" "$ENV_FILE"
  else
    echo "${var}=${value}" >> "$ENV_FILE"
  fi
done

echo "Updated MYSQL_ROOT_PASSWORD, JDBC_PASSWORD, OBA_API_KEY in ${ENV_FILE}"
