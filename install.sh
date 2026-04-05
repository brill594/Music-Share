#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="${ROOT_DIR}/backend"
FRONTEND_DIR="${ROOT_DIR}/web-player"
BACKEND_SETUP_SCRIPT="${BACKEND_DIR}/setup.sh"
ROOT_START_SCRIPT="${ROOT_DIR}/start.sh"
BACKEND_VENV_DIR="${BACKEND_DIR}/.venv"
FRONTEND_DIST_DIR="${FRONTEND_DIR}/dist"
CERTBOT_BASE_DIR="${BACKEND_DIR}/data/letsencrypt"
CERTBOT_CONFIG_DIR="${CERTBOT_BASE_DIR}/config"
CERTBOT_WORK_DIR="${CERTBOT_BASE_DIR}/work"
CERTBOT_LOGS_DIR="${CERTBOT_BASE_DIR}/logs"
SERVICE_NAME=""
SERVICE_UNIT=""
SERVICE_USER="${MUSIC_SHARE_SERVICE_USER:-music-share}"
SERVICE_DATA_ROOT="${MUSIC_SHARE_SERVICE_DATA_ROOT:-/var/lib/music-share}"
SERVICE_UNIT_PATH=""
SERVICE_WRAPPER_PATH=""
CERTBOT_DEPLOY_HOOK_PATH=""
CERTBOT_PRE_HOOK_PATH=""
CERTBOT_POST_HOOK_PATH=""
CERTBOT_RENEW_WRAPPER_PATH=""
CERTBOT_RENEW_SERVICE=""
CERTBOT_RENEW_TIMER=""
CERTBOT_RENEW_SERVICE_PATH=""
CERTBOT_RENEW_TIMER_PATH=""
NOLOGIN_SHELL=""
BACKEND_ENV_FILE="${BACKEND_DIR}/.env"
APT_UPDATED="false"

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
    CERTBOT_DEPLOY_HOOK_PATH="/usr/local/bin/${SERVICE_NAME}-certbot-deploy-hook"
    CERTBOT_PRE_HOOK_PATH="/usr/local/bin/${SERVICE_NAME}-certbot-pre-hook"
    CERTBOT_POST_HOOK_PATH="/usr/local/bin/${SERVICE_NAME}-certbot-post-hook"
    CERTBOT_RENEW_WRAPPER_PATH="/usr/local/bin/${SERVICE_NAME}-certbot-renew"
    CERTBOT_RENEW_SERVICE="${SERVICE_NAME}-certbot-renew.service"
    CERTBOT_RENEW_TIMER="${SERVICE_NAME}-certbot-renew.timer"
    CERTBOT_RENEW_SERVICE_PATH="/etc/systemd/system/${CERTBOT_RENEW_SERVICE}"
    CERTBOT_RENEW_TIMER_PATH="/etc/systemd/system/${CERTBOT_RENEW_TIMER}"
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

update_apt_cache() {
    if [[ "${APT_UPDATED}" == "true" ]]; then
        return
    fi

    apt-get update
    APT_UPDATED="true"
}

python_minor_venv_package() {
    python3 - <<'PY'
import sys
print(f"python{sys.version_info.major}.{sys.version_info.minor}-venv")
PY
}

can_create_temp_venv_with_pip() {
    local temp_dir
    temp_dir="$(mktemp -d)"

    if python3 -m venv "${temp_dir}" >/dev/null 2>&1 && "${temp_dir}/bin/python" -m pip --version >/dev/null 2>&1; then
        rm -rf "${temp_dir}"
        return 0
    fi

    rm -rf "${temp_dir}"
    return 1
}

ensure_python_venv_support_apt() {
    local versioned_pkg
    local packages=("python3-venv")

    if can_create_temp_venv_with_pip; then
        return
    fi

    versioned_pkg="$(python_minor_venv_package)"
    if command_exists apt-cache && apt-cache show "${versioned_pkg}" >/dev/null 2>&1; then
        packages+=("${versioned_pkg}")
    fi

    log "installing Python venv support with apt-get: ${packages[*]}"
    update_apt_cache
    apt-get install -y "${packages[@]}"

    if can_create_temp_venv_with_pip; then
        return
    fi

    if command_exists apt-cache && apt-cache show python3-full >/dev/null 2>&1; then
        log "installing python3-full with apt-get to provide ensurepip"
        update_apt_cache
        apt-get install -y python3-full
    fi

    can_create_temp_venv_with_pip || fail "python3 venv still cannot create an environment with pip; please verify python3-venv is installed correctly"
}

ensure_python_venv_support_dnf() {
    if can_create_temp_venv_with_pip; then
        return
    fi

    log "installing Python venv support with dnf"
    dnf install -y python3 python3-pip
    can_create_temp_venv_with_pip || fail "python3 venv still cannot create an environment with pip; please verify the Python venv packages are installed"
}

install_system_dependencies() {
    local packages=()

    if command_exists apt-get; then
        command_exists python3 || packages+=("python3")
        command_exists node || packages+=("nodejs")
        command_exists npm || packages+=("npm")
        command_exists curl || packages+=("curl")
        command_exists useradd || packages+=("passwd")

        if [[ ${#packages[@]} -gt 0 ]]; then
            log "installing system dependencies with apt-get: ${packages[*]}"
            update_apt_cache
            apt-get install -y "${packages[@]}"
        fi

        ensure_python_venv_support_apt
        return
    fi

    if command_exists dnf; then
        command_exists python3 || packages+=("python3")
        command_exists node || packages+=("nodejs")
        command_exists npm || packages+=("npm")
        command_exists curl || packages+=("curl")
        command_exists useradd || packages+=("shadow-utils")

        if [[ ${#packages[@]} -gt 0 ]]; then
            log "installing system dependencies with dnf: ${packages[*]}"
            dnf install -y "${packages[@]}"
        fi
        ensure_python_venv_support_dnf
        return
    fi

    fail "unsupported Linux package manager, expected apt-get or dnf"
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
    mkdir -p "${SERVICE_DATA_ROOT}/storage"
    chown -R "${SERVICE_USER}:${SERVICE_USER}" "${SERVICE_DATA_ROOT}"
    chmod 751 "${SERVICE_DATA_ROOT}"
    chmod 755 "${SERVICE_DATA_ROOT}/storage"
}

fix_backend_env_permissions() {
    if [[ ! -f "${BACKEND_ENV_FILE}" ]]; then
        return
    fi

    chown root:"${SERVICE_USER}" "${BACKEND_ENV_FILE}"
    chmod 640 "${BACKEND_ENV_FILE}"
}

ensure_backend_venv() {
    if [[ -x "${BACKEND_VENV_DIR}/bin/python" ]]; then
        return
    fi

    if [[ -d "${BACKEND_VENV_DIR}" ]]; then
        log "removing incomplete backend virtualenv at ${BACKEND_VENV_DIR}"
        rm -rf "${BACKEND_VENV_DIR}"
    fi

    log "creating backend virtualenv at ${BACKEND_VENV_DIR}"
    python3 -m venv "${BACKEND_VENV_DIR}"
}

ensure_backend_pip() {
    local python_bin="${BACKEND_VENV_DIR}/bin/python"

    [[ -x "${python_bin}" ]] || fail "backend virtualenv python not found: ${python_bin}"

    if "${python_bin}" -m pip --version >/dev/null 2>&1; then
        return
    fi

    log "pip not found in backend virtualenv, bootstrapping with ensurepip"
    "${python_bin}" -m ensurepip --upgrade || fail "failed to bootstrap pip in ${BACKEND_VENV_DIR}"
    "${python_bin}" -m pip install --upgrade pip setuptools wheel
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

install_certbot_hook_scripts() {
    log "installing certbot hook scripts"

    cat > "${CERTBOT_DEPLOY_HOOK_PATH}" <<'EOF'
#!/usr/bin/env bash

set -euo pipefail

if ! command -v nginx >/dev/null 2>&1; then
    exit 0
fi

if ! nginx -t >/dev/null 2>&1; then
    exit 0
fi

if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files nginx.service >/dev/null 2>&1; then
    if systemctl is-active --quiet nginx; then
        systemctl reload nginx >/dev/null 2>&1 || systemctl restart nginx >/dev/null 2>&1 || true
    fi
    exit 0
fi

nginx -s reload >/dev/null 2>&1 || true
EOF
    chmod 755 "${CERTBOT_DEPLOY_HOOK_PATH}"

    cat > "${CERTBOT_PRE_HOOK_PATH}" <<'EOF'
#!/usr/bin/env bash

set -euo pipefail

if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files nginx.service >/dev/null 2>&1; then
    if systemctl is-active --quiet nginx; then
        systemctl stop nginx >/dev/null 2>&1 || true
    fi
    exit 0
fi

if command -v nginx >/dev/null 2>&1 && pgrep -x nginx >/dev/null 2>&1; then
    nginx -s quit >/dev/null 2>&1 || true
fi
EOF
    chmod 755 "${CERTBOT_PRE_HOOK_PATH}"

    cat > "${CERTBOT_POST_HOOK_PATH}" <<'EOF'
#!/usr/bin/env bash

set -euo pipefail

if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files nginx.service >/dev/null 2>&1; then
    systemctl start nginx >/dev/null 2>&1 || systemctl restart nginx >/dev/null 2>&1 || true
    exit 0
fi

if command -v nginx >/dev/null 2>&1; then
    nginx >/dev/null 2>&1 || true
fi
EOF
    chmod 755 "${CERTBOT_POST_HOOK_PATH}"
}

install_certbot_renew_wrapper() {
    log "installing certbot renew wrapper: ${CERTBOT_RENEW_WRAPPER_PATH}"
    cat > "${CERTBOT_RENEW_WRAPPER_PATH}" <<EOF
#!/usr/bin/env bash

set -euo pipefail

CERTBOT_BIN="\$(command -v certbot)"
CONFIG_DIR="${CERTBOT_CONFIG_DIR}"
WORK_DIR="${CERTBOT_WORK_DIR}"
LOGS_DIR="${CERTBOT_LOGS_DIR}"
DEPLOY_HOOK="${CERTBOT_DEPLOY_HOOK_PATH}"
PRE_HOOK="${CERTBOT_PRE_HOOK_PATH}"
POST_HOOK="${CERTBOT_POST_HOOK_PATH}"

[[ -x "\${CERTBOT_BIN}" ]] || {
    printf '[certbot-renew] error: certbot not found in PATH\n' >&2
    exit 1
}

mkdir -p "\${CONFIG_DIR}" "\${WORK_DIR}" "\${LOGS_DIR}"

certbot_args=(
    renew
    --non-interactive
    --config-dir "\${CONFIG_DIR}"
    --work-dir "\${WORK_DIR}"
    --logs-dir "\${LOGS_DIR}"
    --deploy-hook "\${DEPLOY_HOOK}"
)

shopt -s nullglob
renewal_files=("\${CONFIG_DIR}/renewal/"*.conf)
if (( \${#renewal_files[@]} > 0 )) && grep -Eq '^[[:space:]]*authenticator[[:space:]]*=[[:space:]]*standalone[[:space:]]*$' "\${renewal_files[@]}"; then
    certbot_args+=(--pre-hook "\${PRE_HOOK}" --post-hook "\${POST_HOOK}")
fi

exec "\${CERTBOT_BIN}" "\${certbot_args[@]}"
EOF
    chmod 755 "${CERTBOT_RENEW_WRAPPER_PATH}"
}

install_certbot_renew_unit() {
    log "installing certbot renew systemd service: ${CERTBOT_RENEW_SERVICE_PATH}"
    cat > "${CERTBOT_RENEW_SERVICE_PATH}" <<EOF
[Unit]
Description=Renew Music Share TLS certificates
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
ExecStart=${CERTBOT_RENEW_WRAPPER_PATH}
EOF
}

install_certbot_renew_timer() {
    log "installing certbot renew systemd timer: ${CERTBOT_RENEW_TIMER_PATH}"
    cat > "${CERTBOT_RENEW_TIMER_PATH}" <<EOF
[Unit]
Description=Run Music Share certificate renewal twice daily

[Timer]
OnCalendar=*-*-* 03,15:17:00
RandomizedDelaySec=45m
Persistent=true
Unit=${CERTBOT_RENEW_SERVICE}

[Install]
WantedBy=timers.target
EOF
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
UMask=0077

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

enable_certbot_renew_timer() {
    log "enabling certbot renew timer: ${CERTBOT_RENEW_TIMER}"
    systemctl enable --now "${CERTBOT_RENEW_TIMER}" >/dev/null
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
    MUSIC_SHARE_SERVICE_USER="${SERVICE_USER}" \
    MUSIC_SHARE_CERTBOT_DEPLOY_HOOK="${CERTBOT_DEPLOY_HOOK_PATH}" \
    MUSIC_SHARE_CERTBOT_PRE_HOOK="${CERTBOT_PRE_HOOK_PATH}" \
    MUSIC_SHARE_CERTBOT_POST_HOOK="${CERTBOT_POST_HOOK_PATH}" \
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
    install_system_dependencies
    require_command python3
    require_command node
    require_command npm
    require_command curl
    require_command systemctl
    require_command useradd
    resolve_nologin_shell

    ensure_service_user
    prepare_service_data_root
    install_certbot_hook_scripts
    ensure_backend_venv
    ensure_backend_pip
    install_backend_dependencies
    install_frontend_dependencies
    build_frontend
    run_backend_setup
    fix_backend_env_permissions
    prepare_service_data_root
    install_systemd_wrapper
    install_systemd_unit
    install_certbot_renew_wrapper
    install_certbot_renew_unit
    install_certbot_renew_timer
    reload_systemd
    enable_backend_service
    enable_certbot_renew_timer
    enable_nginx_service_if_available
    start_services

    log "install complete"
    log "systemd unit: ${SERVICE_UNIT}"
    log "certbot renew timer: ${CERTBOT_RENEW_TIMER}"
    log "service user: ${SERVICE_USER}"
    log "backend data root: ${SERVICE_DATA_ROOT}"
    log "frontend dist: ${FRONTEND_DIST_DIR}"
    log "backend venv: ${BACKEND_VENV_DIR}"
}

main "$@"
