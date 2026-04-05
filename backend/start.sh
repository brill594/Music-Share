#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"
DEFAULT_DATA_ROOT="${SCRIPT_DIR}/data"
DATA_ROOT=""
LOG_DIR=""
RUN_DIR=""
PID_FILE=""
LOG_FILE=""
FOREGROUND="false"

log() {
    printf '[start] %s\n' "$*"
}

fail() {
    printf '[start] error: %s\n' "$*" >&2
    exit 1
}

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

is_root() {
    [[ "$(id -u)" -eq 0 ]]
}

load_env() {
    if [[ -f "${ENV_FILE}" ]]; then
        set -a
        # shellcheck disable=SC1090
        source "${ENV_FILE}"
        set +a
        log "loaded ${ENV_FILE}"
    fi
}

resolve_runtime_paths() {
    local configured_data_root="${MUSIC_SHARE_DATA_ROOT:-${DEFAULT_DATA_ROOT}}"
    DATA_ROOT="${configured_data_root%/}"
    LOG_DIR="${DATA_ROOT}/logs"
    RUN_DIR="${DATA_ROOT}/run"
    PID_FILE="${RUN_DIR}/backend.pid"
    LOG_FILE="${LOG_DIR}/backend.log"
}

pick_python() {
    if [[ -x "${SCRIPT_DIR}/.venv/bin/python" ]]; then
        printf '%s' "${SCRIPT_DIR}/.venv/bin/python"
    elif command_exists python3; then
        printf '%s' "$(command -v python3)"
    else
        fail "python3 not found"
    fi
}

ensure_python_dependencies() {
    local python_bin="$1"
    "${python_bin}" - <<'PY'
import importlib
import sys

for module in ("fastapi", "uvicorn"):
    try:
        importlib.import_module(module)
    except ModuleNotFoundError:
        raise SystemExit(f"missing python module: {module}")
PY
}

is_process_alive() {
    local pid="$1"
    kill -0 "${pid}" >/dev/null 2>&1
}

start_backend_background() {
    local python_bin="$1"
    mkdir -p "${LOG_DIR}" "${RUN_DIR}"

    if [[ -f "${PID_FILE}" ]]; then
        local existing_pid
        existing_pid="$(cat "${PID_FILE}")"
        if [[ -n "${existing_pid}" ]] && is_process_alive "${existing_pid}"; then
            log "backend already running with pid ${existing_pid}"
            return
        fi
        rm -f "${PID_FILE}"
    fi

    cd "${SCRIPT_DIR}"
    nohup "${python_bin}" -m uvicorn app.asgi:app --host "${APP_HOST}" --port "${APP_PORT}" >>"${LOG_FILE}" 2>&1 &
    local pid=$!
    printf '%s\n' "${pid}" > "${PID_FILE}"
    sleep 1

    if ! is_process_alive "${pid}"; then
        rm -f "${PID_FILE}"
        fail "backend failed to start, check ${LOG_FILE}"
    fi

    log "backend started with pid ${pid}"
    log "backend log: ${LOG_FILE}"
}

start_backend_foreground() {
    local python_bin="$1"
    cd "${SCRIPT_DIR}"
    exec "${python_bin}" -m uvicorn app.asgi:app --host "${APP_HOST}" --port "${APP_PORT}"
}

reload_nginx_if_available() {
    local nginx_bin=""
    if command_exists nginx; then
        nginx_bin="$(command -v nginx)"
    fi

    if [[ -z "${nginx_bin}" ]]; then
        log "nginx not found in PATH, skipping reload"
        return
    fi

    if ! is_root; then
        log "not running as root, skipping nginx reload/start"
        return
    fi

    if "${nginx_bin}" -t >/dev/null 2>&1; then
        if "${nginx_bin}" -s reload >/dev/null 2>&1; then
            log "nginx reloaded"
        else
            "${nginx_bin}"
            log "nginx started"
        fi
    else
        log "nginx config test failed, skipping nginx reload"
    fi
}

main() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --foreground)
                FOREGROUND="true"
                shift
                ;;
            *)
                fail "unknown argument: $1"
                ;;
        esac
    done

    load_env
    resolve_runtime_paths
    local app_host="${MUSIC_SHARE_BIND_HOST:-127.0.0.1}"
    local app_port="${MUSIC_SHARE_BIND_PORT:-2087}"
    local python_bin
    python_bin="$(pick_python)"
    ensure_python_dependencies "${python_bin}"

    if [[ "${FOREGROUND}" == "true" ]]; then
        APP_HOST="${app_host}"
        APP_PORT="${app_port}"
        log "starting backend in foreground on ${APP_HOST}:${APP_PORT}"
        start_backend_foreground "${python_bin}"
    fi

    APP_HOST="${app_host}"
    APP_PORT="${app_port}"
    log "starting backend in background on ${APP_HOST}:${APP_PORT}"
    start_backend_background "${python_bin}"
    reload_nginx_if_available
}

main "$@"
