#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="${ROOT_DIR}/backend"
FRONTEND_DIR="${ROOT_DIR}/web-player"
BACKEND_SETUP_SCRIPT="${BACKEND_DIR}/setup.sh"
ROOT_START_SCRIPT="${ROOT_DIR}/start.sh"
BACKEND_VENV_DIR="${BACKEND_DIR}/.venv"
FRONTEND_DIST_DIR="${FRONTEND_DIR}/dist"
SERVICE_NAME=""
SERVICE_UNIT=""
SERVICE_USER="${MUSIC_SHARE_SERVICE_USER:-music-share}"
SERVICE_DATA_ROOT="${MUSIC_SHARE_SERVICE_DATA_ROOT:-/var/lib/music-share}"
SERVICE_UNIT_PATH=""
SERVICE_WRAPPER_PATH=""
NOLOGIN_SHELL=""

log() {
    printf '[install] %s\n' "$*"
}

fail() {
    printf '[install] error: %s\n' "$*" >&2
    exit 1
}

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

require_command() {
    local cmd="$1"
    command_exists "$cmd" || fail "missing required command: ${cmd}"
}

ensure_root() {
    if [[ "$(id -u)" -ne 0 ]]; then
        fail "install.sh 需要 root 权限，请使用 root 用户或 sudo 执行，例如：sudo bash ./install.sh"
    fi
}

ensure_linux() {
    [[ "$(uname -s)" == "Linux" ]] || fail "install.sh 只支持 Linux 服务器"
}

resolve_service_settings() {
    SERVICE_NAME="${MUSIC_SHARE_SYSTEMD_SERVICE_NAME:-music-share-backend}"
    SERVICE_NAME="${SERVICE_NAME%.service}"
    SERVICE_UNIT="${SERVICE_NAME}.service"
    SERVICE_UNIT_PATH="/etc/systemd/system/${SERVICE_UNIT}"
    SERVICE_WRAPPER_PATH="/usr/local/bin/${SERVICE_NAME}-run"
}

ensure_required_files() {
    [[ -f "${BACKEND_SETUP_SCRIPT}" ]] || fail "missing backend setup script: ${BACKEND_SETUP_SCRIPT}"
    [[ -f "${ROOT_START_SCRIPT}" ]] || fail "missing root start script: ${ROOT_START_SCRIPT}"
    [[ -f "${FRONTEND_DIR}/package.json" ]] || fail "missing frontend package.json"
    [[ -f "${BACKEND_DIR}/pyproject.toml" ]] || fail "missing backend pyproject.toml"
}

resolve_nologin_shell() {
    if command_exists nologin; then
        NOLOGIN_SHELL="$(command -v nologin)"
        return
    fi

    if [[ -x /usr/sbin/nologin ]]; then
        NOLOGIN_SHELL="/usr/sbin/nologin"
        return
    fi

    if [[ -x /sbin/nologin ]]; then
        NOLOGIN_SHELL="/sbin/nologin"
        return
    fi

    fail "nologin shell not found"
}

ensure_service_user() {
    if id -u "${SERVICE_USER}" >/dev/null 2>&1; then
        log "service user already exists: ${SERVICE_USER}"
        return
    fi

    log "creating system user: ${SERVICE_USER}"
    useradd --system --user-group --home-dir "${SERVICE_DATA_ROOT}" --shell "${NOLOGIN_SHELL}" "${SERVICE_USER}"
}

prepare_service_data_root() {
    log "preparing backend data root: ${SERVICE_DATA_ROOT}"
    mkdir -p "${SERVICE_DATA_ROOT}"
    chown -R "${SERVICE_USER}:${SERVICE_USER}" "${SERVICE_DATA_ROOT}"
    chmod 750 "${SERVICE_DATA_ROOT}"
}

ensure_backend_venv() {
    if [[ -x "${BACKEND_VENV_DIR}/bin/python" ]]; then
        return
    fi

    log "creating backend virtualenv at ${BACKEND_VENV_DIR}"
    python3 -m venv "${BACKEND_VENV_DIR}"
}

install_backend_dependencies() {
    local python_bin="${BACKEND_VENV_DIR}/bin/python"
    log "installing backend dependencies"
    "${python_bin}" -m pip install -e "${BACKEND_DIR}"
}

install_frontend_dependencies() {
    log "installing frontend dependencies"
    if [[ -f "${FRONTEND_DIR}/package-lock.json" ]]; then
        (
            cd "${FRONTEND_DIR}"
            npm ci
        )
        return
    fi

    (
        cd "${FRONTEND_DIR}"
        npm install
    )
}

build_frontend() {
    log "building frontend"
    (
        cd "${FRONTEND_DIR}"
        npm run build
    )

    [[ -d "${FRONTEND_DIST_DIR}" ]] || fail "frontend build output not found: ${FRONTEND_DIST_DIR}"
}

install_systemd_wrapper() {
    log "installing systemd wrapper: ${SERVICE_WRAPPER_PATH}"
    cat > "${SERVICE_WRAPPER_PATH}" <<EOF
#!/usr/bin/env bash

set -euo pipefail

exec bash "${BACKEND_DIR}/start.sh" --foreground
EOF
    chmod 755 "${SERVICE_WRAPPER_PATH}"
}

install_systemd_unit() {
    log "installing systemd unit: ${SERVICE_UNIT_PATH}"
    cat > "${SERVICE_UNIT_PATH}" <<EOF
[Unit]
Description=Music Share Backend
After=network-online.target nginx.service
Wants=network-online.target

[Service]
Type=simple
User=${SERVICE_USER}
Group=${SERVICE_USER}
ExecStart=${SERVICE_WRAPPER_PATH}
Restart=on-failure
RestartSec=3
TimeoutStopSec=20
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
EOF
}

reload_systemd() {
    log "reloading systemd"
    systemctl daemon-reload
}

enable_backend_service() {
    log "enabling backend service: ${SERVICE_UNIT}"
    systemctl enable "${SERVICE_UNIT}" >/dev/null
}

enable_nginx_service_if_available() {
    if systemctl list-unit-files nginx.service >/dev/null 2>&1; then
        log "enabling nginx service"
        systemctl enable nginx >/dev/null
    fi
}

run_backend_setup() {
    log "running backend HTTPS and nginx setup"
    MUSIC_SHARE_FRONTEND_DIST_DIR="${FRONTEND_DIST_DIR}" \
    MUSIC_SHARE_DATA_ROOT="${SERVICE_DATA_ROOT}" \
    bash "${BACKEND_SETUP_SCRIPT}"
}

start_services() {
    log "starting deployed services"
    MUSIC_SHARE_SYSTEMD_SERVICE_NAME="${SERVICE_NAME}" bash "${ROOT_START_SCRIPT}" restart
}

main() {
    ensure_root
    ensure_linux
    resolve_service_settings
    ensure_required_files
    require_command python3
    require_command node
    require_command npm
    require_command systemctl
    require_command useradd
    resolve_nologin_shell

    ensure_service_user
    prepare_service_data_root
    ensure_backend_venv
    install_backend_dependencies
    install_frontend_dependencies
    build_frontend
    run_backend_setup
    prepare_service_data_root
    install_systemd_wrapper
    install_systemd_unit
    reload_systemd
    enable_backend_service
    enable_nginx_service_if_available
    start_services

    log "install complete"
    log "systemd unit: ${SERVICE_UNIT}"
    log "service user: ${SERVICE_USER}"
    log "backend data root: ${SERVICE_DATA_ROOT}"
    log "frontend dist: ${FRONTEND_DIST_DIR}"
    log "backend venv: ${BACKEND_VENV_DIR}"
}

main "$@"
