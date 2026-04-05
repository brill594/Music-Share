#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_NAME="${MUSIC_SHARE_SYSTEMD_SERVICE_NAME:-music-share-backend}"
SERVICE_NAME="${SERVICE_NAME%.service}"
SERVICE_UNIT="${SERVICE_NAME}.service"
SERVICE_UNIT_PATH="/etc/systemd/system/${SERVICE_UNIT}"
ACTION="${1:-restart}"

log() {
    printf '[root-start] %s\n' "$*"
}

fail() {
    printf '[root-start] error: %s\n' "$*" >&2
    exit 1
}

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

ensure_root() {
    if [[ "$(id -u)" -ne 0 ]]; then
        fail "start.sh 需要 root 权限，请使用 root 用户或 sudo 执行，例如：sudo bash ./start.sh"
    fi
}

ensure_systemd() {
    command_exists systemctl || fail "systemctl not found"
}

ensure_service_installed() {
    [[ -f "${SERVICE_UNIT_PATH}" ]] || fail "systemd unit not found: ${SERVICE_UNIT_PATH}，请先执行 sudo bash ./install.sh"
}

start_nginx_if_available() {
    if systemctl list-unit-files nginx.service >/dev/null 2>&1; then
        systemctl start nginx
    fi
}

run_action() {
    case "${ACTION}" in
        start)
            start_nginx_if_available
            systemctl start "${SERVICE_UNIT}"
            ;;
        restart)
            start_nginx_if_available
            if systemctl is-active --quiet "${SERVICE_UNIT}"; then
                systemctl restart "${SERVICE_UNIT}"
            else
                systemctl start "${SERVICE_UNIT}"
            fi
            ;;
        stop)
            systemctl stop "${SERVICE_UNIT}"
            ;;
        status)
            systemctl status "${SERVICE_UNIT}" --no-pager
            ;;
        logs)
            journalctl -u "${SERVICE_UNIT}" -n 100 -f
            ;;
        *)
            fail "unknown action: ${ACTION}. supported actions: start, restart, stop, status, logs"
            ;;
    esac
}

main() {
    ensure_root
    ensure_systemd
    ensure_service_installed
    systemctl daemon-reload

    log "project root: ${ROOT_DIR}"
    log "service unit: ${SERVICE_UNIT}"
    run_action
}

main "$@"
