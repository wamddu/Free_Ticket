#!/usr/bin/env bash
# Runs on the server (as root, sent by GitHub Actions through AWS SSM Run Command).
#   deploy.sh <commit-sha>
# 1. stops if someone edited files on the server
# 2. backs up the database
# 3. moves the checkout to <commit-sha> and pulls the images CI built for it from GHCR
# 4. restarts the containers and waits for /health to say UP
# 5. if /health never comes up, returns to the previous commit and images
set -euo pipefail

SHA="${1:?usage: deploy.sh <commit-sha>}"
REPO_DIR="${REPO_DIR:-/home/ubuntu/Free_Ticket}"
APP_USER="${APP_USER:-ubuntu}"
BACKUP_DIR="/home/${APP_USER}/backups"
STATE_FILE="/home/${APP_USER}/.free-ticket-deployed-tag"
KEEP_BACKUPS=10
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-240}"

cd "${REPO_DIR}/code/backend"

as_app() { sudo -u "${APP_USER}" "$@"; }
compose() { docker compose --env-file .env.deploy -f docker-compose.deploy.yml "$@"; }
log() { echo "[deploy $(date '+%H:%M:%S')] $*"; }

PUBLIC_HOST="$(grep -E '^PUBLIC_HOST=' .env.deploy | cut -d= -f2-)"

healthy() {
    # Through Caddy on this machine, so the check covers the proxy and TLS as well as the backend.
    curl -fsS --max-time 5 --resolve "${PUBLIC_HOST}:443:127.0.0.1" "https://${PUBLIC_HOST}/health" 2>/dev/null \
        | grep -q '"status":"UP"'
}

wait_healthy() {
    local waited=0
    until healthy; do
        if (( waited >= HEALTH_TIMEOUT_SECONDS )); then
            return 1
        fi
        sleep 5
        waited=$((waited + 5))
    done
    log "health UP after ${waited}s"
}

start_images() {
    IMAGE_TAG="$1" compose pull --quiet backend web
    IMAGE_TAG="$1" compose up -d --no-build
}

# 1. Server-side edits would be wiped by the reset below.
if [[ -n "$(as_app git status --porcelain --untracked-files=no)" ]]; then
    log "files were edited on the server; commit them or restore them first:"
    as_app git status --short --untracked-files=no
    exit 1
fi

PREV_SHA="$(as_app git rev-parse HEAD)"
PREV_TAG="$(cat "${STATE_FILE}" 2>/dev/null || true)"
log "current ${PREV_SHA:0:7} (images: ${PREV_TAG:-built on server}), deploying ${SHA:0:7}"

# Download first: if GHCR is unreachable or the images are private, stop before changing anything.
if ! IMAGE_TAG="${SHA}" compose pull --quiet backend web; then
    log "could not pull the ${SHA:0:7} images from GHCR (are the packages public?); nothing was changed"
    exit 1
fi

# 2. Backup. Flyway migrations in the new version may change tables.
as_app mkdir -p "${BACKUP_DIR}"
BACKUP_FILE="${BACKUP_DIR}/free_ticket-$(date +%Y%m%d-%H%M%S)-${SHA:0:7}.sql"
compose exec -T mysql-db sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -u root --single-transaction --routines free_ticket' \
    | as_app tee "${BACKUP_FILE}" > /dev/null
log "backup $(du -h "${BACKUP_FILE}" | cut -f1) ${BACKUP_FILE}"
ls -1t "${BACKUP_DIR}"/free_ticket-*.sql | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -f

# 3-4. New version.
as_app git fetch --quiet origin
as_app git reset --quiet --hard "${SHA}"
start_images "${SHA}"

if wait_healthy; then
    echo "${SHA}" | as_app tee "${STATE_FILE}" > /dev/null
    docker image prune -f > /dev/null
    log "deployed ${SHA:0:7}"
    exit 0
fi

# 5. Roll back.
log "health did not become UP within ${HEALTH_TIMEOUT_SECONDS}s; backend log:"
compose logs --tail 80 backend || true
as_app git reset --quiet --hard "${PREV_SHA}"
if [[ -n "${PREV_TAG}" ]]; then
    log "rolling back to ${PREV_TAG:0:7}"
    start_images "${PREV_TAG}"
else
    log "no earlier GHCR images; rebuilding ${PREV_SHA:0:7} on the server"
    compose up -d --build
fi
if wait_healthy; then
    log "rolled back to ${PREV_SHA:0:7}. If the failed version ran a migration, restore ${BACKUP_FILE}"
else
    log "rollback is not healthy either; check the server now"
fi
exit 1
