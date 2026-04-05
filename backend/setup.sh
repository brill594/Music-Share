#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP_DATA_DIR="${SCRIPT_DIR}/data"
APP_DATA_ROOT=""
STORAGE_DIR=""
ACME_WEBROOT="${SETUP_DATA_DIR}/certbot-www"
ACME_CHALLENGE_DIR="${ACME_WEBROOT}/.well-known/acme-challenge"
LE_BASE_DIR="${SETUP_DATA_DIR}/letsencrypt"
LE_CONFIG_DIR="${LE_BASE_DIR}/config"
LE_WORK_DIR="${LE_BASE_DIR}/work"
LE_LOGS_DIR="${LE_BASE_DIR}/logs"
CLOUDFLARE_CREDENTIALS_FILE="${LE_BASE_DIR}/cloudflare.ini"
ENV_FILE="${SCRIPT_DIR}/.env"
BACKEND_UPSTREAM_HOST="${MUSIC_SHARE_BACKEND_HOST:-127.0.0.1}"
BACKEND_UPSTREAM_PORT="${MUSIC_SHARE_BACKEND_PORT:-2087}"
SITE_NAME="${MUSIC_SHARE_NGINX_SITE_NAME:-music-share.conf}"
CERTBOT_MODE="${MUSIC_SHARE_CERTBOT_MODE:-auto}"
CLOUDFLARE_PROPAGATION_SECONDS="${MUSIC_SHARE_CLOUDFLARE_PROPAGATION_SECONDS:-30}"
DOMAIN_CHECK_PATH="/.well-known/music-share-domain-check.txt"

NGINX_BIN=""
CERTBOT_BIN=""
NGINX_MAIN_CONF=""
NGINX_SITE_CONF=""
NGINX_SITE_LINK=""
FRONTEND_DIST_DIR=""

log() {
    printf '[setup] %s\n' "$*"
}

fail() {
    printf '[setup] error: %s\n' "$*" >&2
    exit 1
}

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

run_root() {
    if [[ "$(id -u)" -eq 0 ]]; then
        "$@"
    else
        sudo "$@"
    fi
}

require_command() {
    local cmd="$1"
    command_exists "$cmd" || fail "missing required command: ${cmd}"
}

ensure_root() {
    if [[ "$(id -u)" -ne 0 ]]; then
        fail "setup.sh 需要 root 权限，请使用 root 用户或 sudo 执行，例如：sudo bash ./setup.sh"
    fi
}

ensure_linux() {
    [[ "$(uname -s)" == "Linux" ]] || fail "setup.sh 只支持 Linux 服务器"
}

validate_certbot_mode() {
    case "${CERTBOT_MODE}" in
        auto|webroot|standalone|dns-cloudflare)
            ;;
        *)
            fail "unsupported certbot mode: ${CERTBOT_MODE}, expected auto, webroot, standalone or dns-cloudflare"
            ;;
    esac
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

install_dependencies() {
    if command_exists apt-get; then
        log "installing nginx and certbot with apt-get"
        run_root apt-get update
        run_root apt-get install -y nginx certbot curl
        if ! run_root apt-get install -y python3-certbot-dns-cloudflare; then
            log "optional package python3-certbot-dns-cloudflare not installed; dns-cloudflare mode will require manual plugin installation"
        fi
    elif command_exists dnf; then
        log "installing nginx and certbot with dnf"
        run_root dnf install -y nginx certbot curl
        if ! run_root dnf install -y python3-certbot-dns-cloudflare; then
            log "optional package python3-certbot-dns-cloudflare not installed; dns-cloudflare mode will require manual plugin installation"
        fi
    else
        fail "unsupported Linux package manager, expected apt-get or dnf"
    fi
}

resolve_paths() {
    NGINX_BIN="$(command -v nginx)"
    CERTBOT_BIN="$(command -v certbot)"
    NGINX_MAIN_CONF="/etc/nginx/nginx.conf"

    [[ -x "${NGINX_BIN}" ]] || fail "nginx binary not found at ${NGINX_BIN}"
    [[ -x "${CERTBOT_BIN}" ]] || fail "certbot binary not found at ${CERTBOT_BIN}"
    [[ -f "${NGINX_MAIN_CONF}" ]] || fail "nginx main config not found at ${NGINX_MAIN_CONF}"

    if grep -Eq 'include\s+/etc/nginx/sites-enabled/\*' "${NGINX_MAIN_CONF}"; then
        NGINX_SITE_CONF="/etc/nginx/sites-available/${SITE_NAME}"
        NGINX_SITE_LINK="/etc/nginx/sites-enabled/${SITE_NAME}"
        return
    fi

    if grep -Eq 'include\s+/etc/nginx/conf\.d/\*\.conf' "${NGINX_MAIN_CONF}"; then
        NGINX_SITE_CONF="/etc/nginx/conf.d/${SITE_NAME}"
        NGINX_SITE_LINK=""
        return
    fi

    fail "unsupported nginx include layout in ${NGINX_MAIN_CONF}"
}

resolve_app_paths() {
    local configured_data_root="${MUSIC_SHARE_DATA_ROOT:-${SCRIPT_DIR}/data}"
    APP_DATA_ROOT="${configured_data_root%/}"
    STORAGE_DIR="${APP_DATA_ROOT}/storage"
}

resolve_frontend_dist() {
    local requested_dir="${MUSIC_SHARE_FRONTEND_DIST_DIR:-}"
    local default_dir="${SCRIPT_DIR}/../web-player/dist"

    if [[ -n "${requested_dir}" ]]; then
        [[ -d "${requested_dir}" ]] || fail "frontend dist dir not found: ${requested_dir}"
        FRONTEND_DIST_DIR="$(cd -- "${requested_dir}" && pwd)"
        return
    fi

    if [[ -d "${default_dir}" ]]; then
        FRONTEND_DIST_DIR="$(cd -- "${default_dir}" && pwd)"
    else
        FRONTEND_DIST_DIR=""
    fi
}

prompt_value() {
    local prompt_text="$1"
    local current_value="$2"
    local answer=""
    if [[ -n "${current_value}" ]]; then
        printf '%s [%s]: ' "${prompt_text}" "${current_value}" >&2
    else
        printf '%s: ' "${prompt_text}" >&2
    fi
    read -r answer
    if [[ -n "${answer}" ]]; then
        printf '%s' "${answer}"
    else
        printf '%s' "${current_value}"
    fi
}

prompt_secret_value() {
    local prompt_text="$1"
    local current_value="$2"
    local answer=""
    if [[ -n "${current_value}" ]]; then
        printf '%s [已存在，回车复用]: ' "${prompt_text}" >&2
    else
        printf '%s: ' "${prompt_text}" >&2
    fi
    read -r -s answer
    printf '\n' >&2
    if [[ -n "${answer}" ]]; then
        printf '%s' "${answer}"
    else
        printf '%s' "${current_value}"
    fi
}

normalize_domain_input() {
    local value="$1"

    value="${value//$'\r'/}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    value="$(printf '%s' "${value}" | tr '[:upper:]' '[:lower:]')"
    value="${value#http://}"
    value="${value#https://}"
    value="${value%%/*}"
    value="${value%%\?*}"
    value="${value%%#*}"
    value="${value%%:*}"
    value="${value%.}"
    printf '%s' "${value}"
}

validate_domain_format() {
    local domain="$1"
    [[ "${domain}" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$ ]]
}

prepare_nginx_layout() {
    run_root mkdir -p "$(dirname "${NGINX_SITE_CONF}")"
    if [[ -n "${NGINX_SITE_LINK}" ]]; then
        run_root mkdir -p "$(dirname "${NGINX_SITE_LINK}")"
    fi
}

ensure_acme_webroot_permissions() {
    mkdir -p "${ACME_WEBROOT}/.well-known" "${ACME_CHALLENGE_DIR}"
    chmod 755 "${ACME_WEBROOT}" "${ACME_WEBROOT}/.well-known" "${ACME_CHALLENGE_DIR}"
}

extract_cloudflare_token_from_file() {
    if [[ ! -f "${CLOUDFLARE_CREDENTIALS_FILE}" ]]; then
        return
    fi

    awk -F '=' '
        $1 ~ /^[[:space:]]*dns_cloudflare_api_token[[:space:]]*$/ {
            value = $2
            sub(/^[[:space:]]+/, "", value)
            sub(/[[:space:]]+$/, "", value)
            print value
            exit
        }
    ' "${CLOUDFLARE_CREDENTIALS_FILE}"
}

ensure_certbot_dns_cloudflare_plugin() {
    if "${CERTBOT_BIN}" plugins 2>/dev/null | grep -q 'dns-cloudflare'; then
        return
    fi

    fail "certbot dns-cloudflare plugin not found; please install python3-certbot-dns-cloudflare and rerun"
}

ensure_cloudflare_credentials() {
    local token="${MUSIC_SHARE_CLOUDFLARE_API_TOKEN:-}"

    if [[ -z "${token}" ]]; then
        token="$(extract_cloudflare_token_from_file)"
    fi

    if [[ -z "${token}" ]]; then
        token="$(prompt_secret_value "请输入 Cloudflare API Token（用于 DNS-01）" "")"
    fi

    [[ -n "${token}" ]] || fail "Cloudflare API Token is required for dns-cloudflare mode"

    mkdir -p "$(dirname "${CLOUDFLARE_CREDENTIALS_FILE}")"
    cat > "${CLOUDFLARE_CREDENTIALS_FILE}" <<EOF
dns_cloudflare_api_token = ${token}
EOF
    chmod 600 "${CLOUDFLARE_CREDENTIALS_FILE}"
    log "cloudflare credentials file ready: ${CLOUDFLARE_CREDENTIALS_FILE}"
}

write_http_only_conf() {
    local domain="$1"
    local output_file="$2"

    if [[ -n "${FRONTEND_DIST_DIR}" ]]; then
        cat > "${output_file}" <<EOF
server {
    listen 80;
    server_name ${domain};
    client_max_body_size 64m;
    root "${FRONTEND_DIST_DIR}";
    index index.html;

    location = ${DOMAIN_CHECK_PATH} {
        default_type text/plain;
        alias "${ACME_WEBROOT}${DOMAIN_CHECK_PATH}";
    }

    location ^~ /.well-known/acme-challenge/ {
        root "${ACME_WEBROOT}";
        default_type text/plain;
        try_files \$uri =404;
    }

    location ^~ /assets/ {
        try_files \$uri =404;
        access_log off;
        expires 7d;
        add_header Cache-Control "public, max-age=604800, immutable";
    }

    location /internal-media/ {
        internal;
        alias "${STORAGE_DIR}/";
    }

    location = /openapi.json {
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_pass http://${BACKEND_UPSTREAM_HOST}:${BACKEND_UPSTREAM_PORT};
    }

    location ~ ^/(auth|upload|client|admin|track|stream|cover|docs|redoc)(/|$) {
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_pass http://${BACKEND_UPSTREAM_HOST}:${BACKEND_UPSTREAM_PORT};
    }

    location / {
        try_files \$uri \$uri/ /index.html;
    }
}
EOF
        return
    fi

    cat > "${output_file}" <<EOF
server {
    listen 80;
    server_name ${domain};
    client_max_body_size 64m;

    location = ${DOMAIN_CHECK_PATH} {
        default_type text/plain;
        alias "${ACME_WEBROOT}${DOMAIN_CHECK_PATH}";
    }

    location ^~ /.well-known/acme-challenge/ {
        root "${ACME_WEBROOT}";
        default_type text/plain;
        try_files \$uri =404;
    }

    location /internal-media/ {
        internal;
        alias "${STORAGE_DIR}/";
    }

    location / {
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_pass http://${BACKEND_UPSTREAM_HOST}:${BACKEND_UPSTREAM_PORT};
    }
}
EOF
}

write_https_conf() {
    local domain="$1"
    local output_file="$2"
    local cert_dir="${LE_CONFIG_DIR}/live/${domain}"

    if [[ -n "${FRONTEND_DIST_DIR}" ]]; then
        cat > "${output_file}" <<EOF
server {
    listen 80;
    server_name ${domain};
    client_max_body_size 64m;

    location ^~ /.well-known/acme-challenge/ {
        root "${ACME_WEBROOT}";
        default_type text/plain;
        try_files \$uri =404;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl;
    http2 on;
    server_name ${domain};
    client_max_body_size 64m;
    root "${FRONTEND_DIST_DIR}";
    index index.html;

    ssl_certificate "${cert_dir}/fullchain.pem";
    ssl_certificate_key "${cert_dir}/privkey.pem";
    ssl_session_timeout 1d;
    ssl_session_cache shared:MusicShareSSL:10m;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;

    location ^~ /assets/ {
        try_files \$uri =404;
        access_log off;
        expires 7d;
        add_header Cache-Control "public, max-age=604800, immutable";
    }

    location /internal-media/ {
        internal;
        alias "${STORAGE_DIR}/";
    }

    location = /openapi.json {
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_pass http://${BACKEND_UPSTREAM_HOST}:${BACKEND_UPSTREAM_PORT};
    }

    location ~ ^/(auth|upload|client|admin|track|stream|cover|docs|redoc)(/|$) {
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_pass http://${BACKEND_UPSTREAM_HOST}:${BACKEND_UPSTREAM_PORT};
    }

    location / {
        try_files \$uri \$uri/ /index.html;
    }
}
EOF
        return
    fi

    cat > "${output_file}" <<EOF
server {
    listen 80;
    server_name ${domain};
    client_max_body_size 64m;

    location ^~ /.well-known/acme-challenge/ {
        root "${ACME_WEBROOT}";
        default_type text/plain;
        try_files \$uri =404;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl;
    http2 on;
    server_name ${domain};
    client_max_body_size 64m;

    ssl_certificate "${cert_dir}/fullchain.pem";
    ssl_certificate_key "${cert_dir}/privkey.pem";
    ssl_session_timeout 1d;
    ssl_session_cache shared:MusicShareSSL:10m;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;

    location /internal-media/ {
        internal;
        alias "${STORAGE_DIR}/";
    }

    location / {
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_pass http://${BACKEND_UPSTREAM_HOST}:${BACKEND_UPSTREAM_PORT};
    }
}
EOF
}

install_site_conf() {
    local src_conf="$1"
    run_root cp "${src_conf}" "${NGINX_SITE_CONF}"
    if [[ -n "${NGINX_SITE_LINK}" ]]; then
        run_root ln -sfn "${NGINX_SITE_CONF}" "${NGINX_SITE_LINK}"
    fi
}

test_nginx() {
    run_root "${NGINX_BIN}" -t -c "${NGINX_MAIN_CONF}"
}

reload_or_start_nginx() {
    if command_exists systemctl && systemctl list-unit-files nginx.service >/dev/null 2>&1; then
        if systemctl is-active --quiet nginx; then
            if systemctl reload nginx >/dev/null 2>&1 || systemctl restart nginx >/dev/null 2>&1; then
                log "reloaded nginx with systemd"
                return
            fi
        elif systemctl start nginx >/dev/null 2>&1; then
            log "started nginx with systemd"
            return
        fi
    fi

    if run_root "${NGINX_BIN}" -s reload -c "${NGINX_MAIN_CONF}" >/dev/null 2>&1; then
        log "reloaded nginx"
    else
        run_root "${NGINX_BIN}" -c "${NGINX_MAIN_CONF}"
        log "started nginx"
    fi
}

stop_nginx_if_running() {
    if command_exists systemctl && systemctl list-unit-files nginx.service >/dev/null 2>&1; then
        if systemctl is-active --quiet nginx; then
            systemctl stop nginx
            log "stopped nginx with systemd for standalone certbot"
            return 0
        fi
        return 1
    fi

    if pgrep -x nginx >/dev/null 2>&1; then
        run_root "${NGINX_BIN}" -s quit -c "${NGINX_MAIN_CONF}"
        log "stopped nginx for standalone certbot"
        return 0
    fi

    return 1
}

verify_domain_reaches_nginx() {
    local domain="$1"
    local expected_token="$2"
    local url="http://${domain}${DOMAIN_CHECK_PATH}"
    local actual=""

    sleep 2
    actual="$(curl -fsS --max-time 15 "${url}")" || true
    [[ -n "${actual}" ]] || fail "domain validation failed, cannot fetch ${url}"
    [[ "${actual}" == "${expected_token}" ]] || fail "domain validation failed, fetched content did not match expected token"
    log "domain validation passed: ${domain} is reaching this nginx instance"
}

log_certbot_failure_guidance() {
    log "certificate request failed in webroot, standalone and dns-cloudflare modes"
    log "this usually means the domain or Cloudflare token configuration still has an issue"
    log "please check upstream WAF/CDN/reverse proxy/FRP/load balancer settings for the domain"
    log "also confirm DNS A/AAAA records only point to this server"
    log "if the domain is behind an HTTP proxy, disable the proxy during certificate issuance or use dns-cloudflare mode"
    log "for Cloudflare DNS, make sure the API token has Zone.DNS Edit and Zone.Zone Read permissions for the target zone"
}

run_certbot_once() {
    local mode="$1"
    local domain="$2"
    local email="$3"
    local -a certbot_args=(
        certonly
        -d "${domain}"
        --agree-tos
        --non-interactive
        --keep-until-expiring
        --config-dir "${LE_CONFIG_DIR}"
        --work-dir "${LE_WORK_DIR}"
        --logs-dir "${LE_LOGS_DIR}"
    )
    local nginx_was_running="false"
    local certbot_rc=0

    case "${mode}" in
        webroot)
            certbot_args+=(--webroot -w "${ACME_WEBROOT}")
            ;;
        standalone)
            certbot_args+=(--standalone --preferred-challenges http)
            if stop_nginx_if_running; then
                nginx_was_running="true"
            fi
            ;;
        dns-cloudflare)
            ensure_certbot_dns_cloudflare_plugin
            ensure_cloudflare_credentials
            certbot_args+=(
                --dns-cloudflare
                --dns-cloudflare-credentials "${CLOUDFLARE_CREDENTIALS_FILE}"
                --dns-cloudflare-propagation-seconds "${CLOUDFLARE_PROPAGATION_SECONDS}"
                --preferred-challenges dns-01
            )
            ;;
    esac

    if [[ -n "${email}" ]]; then
        certbot_args+=(--email "${email}")
    else
        certbot_args+=(--register-unsafely-without-email)
    fi

    log "requesting certificate for ${domain} with certbot mode=${mode}"
    if run_root "${CERTBOT_BIN}" "${certbot_args[@]}"; then
        certbot_rc=0
    else
        certbot_rc=$?
    fi

    if [[ "${nginx_was_running}" == "true" ]]; then
        reload_or_start_nginx
    fi

    if [[ "${certbot_rc}" -ne 0 ]]; then
        return "${certbot_rc}"
    fi
}

run_certbot() {
    local domain="$1"
    local email="$2"

    case "${CERTBOT_MODE}" in
        auto)
            if run_certbot_once webroot "${domain}" "${email}"; then
                return 0
            fi
            log "webroot certbot failed, retrying with standalone mode"
            if run_certbot_once standalone "${domain}" "${email}"; then
                return 0
            fi
            log "standalone certbot failed, retrying with dns-cloudflare mode"
            if run_certbot_once dns-cloudflare "${domain}" "${email}"; then
                return 0
            fi
            log_certbot_failure_guidance
            return 1
            ;;
        webroot|standalone|dns-cloudflare)
            run_certbot_once "${CERTBOT_MODE}" "${domain}" "${email}"
            ;;
    esac
}

upsert_env_value() {
    local key="$1"
    local value="$2"
    local temp_file

    temp_file="$(mktemp)"
    python3 - "${ENV_FILE}" "${temp_file}" "${key}" "${value}" <<'PY'
import sys
from pathlib import Path

env_path = Path(sys.argv[1])
tmp_path = Path(sys.argv[2])
key = sys.argv[3]
value = sys.argv[4]
line = f"{key}={value}"

if env_path.exists():
    lines = env_path.read_text(encoding="utf-8").splitlines()
else:
    lines = []

updated = False
result = []
for current in lines:
    if current.startswith(f"{key}="):
        result.append(line)
        updated = True
    else:
        result.append(current)

if not updated:
    result.append(line)

tmp_path.write_text("\n".join(result).strip() + "\n", encoding="utf-8")
PY
    mv "${temp_file}" "${ENV_FILE}"
}

main() {
    ensure_root
    ensure_linux
    validate_certbot_mode
    load_env
    resolve_app_paths
    install_dependencies
    resolve_paths
    resolve_frontend_dist
    prepare_nginx_layout

    mkdir -p "${APP_DATA_ROOT}" "${STORAGE_DIR}" "${ACME_WEBROOT}" "${LE_CONFIG_DIR}" "${LE_WORK_DIR}" "${LE_LOGS_DIR}"
    ensure_acme_webroot_permissions

    if [[ -n "${FRONTEND_DIST_DIR}" ]]; then
        log "frontend dist detected: ${FRONTEND_DIST_DIR}"
    else
        log "frontend dist not found, nginx will proxy backend only"
    fi
    log "backend runtime data root: ${APP_DATA_ROOT}"
    log "certbot mode: ${CERTBOT_MODE}"
    log "请输入纯域名，例如 example.com；也可以直接粘贴 https://example.com/ ，脚本会自动提取域名"

    local domain="${MUSIC_SHARE_DOMAIN:-}"
    local email="${MUSIC_SHARE_CERTBOT_EMAIL:-}"
    domain="$(normalize_domain_input "${domain}")"

    while true; do
        domain="$(normalize_domain_input "$(prompt_value "请输入要绑定的域名" "${domain}")")"
        validate_domain_format "${domain}" && break
        printf '域名格式不合法，请输入纯域名，例如：example.com\n'
    done

    email="$(prompt_value "请输入 Certbot 邮箱，可留空" "${email}")"

    local token
    token="$(python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(24))
PY
)"
    mkdir -p "$(dirname "${ACME_WEBROOT}${DOMAIN_CHECK_PATH}")"
    printf '%s' "${token}" > "${ACME_WEBROOT}${DOMAIN_CHECK_PATH}"
    chmod 644 "${ACME_WEBROOT}${DOMAIN_CHECK_PATH}"

    local temp_http_conf
    temp_http_conf="$(mktemp)"
    write_http_only_conf "${domain}" "${temp_http_conf}"
    install_site_conf "${temp_http_conf}"
    rm -f "${temp_http_conf}"

    test_nginx
    reload_or_start_nginx
    verify_domain_reaches_nginx "${domain}" "${token}"

    run_certbot "${domain}" "${email}"

    local temp_https_conf
    temp_https_conf="$(mktemp)"
    write_https_conf "${domain}" "${temp_https_conf}"
    install_site_conf "${temp_https_conf}"
    rm -f "${temp_https_conf}"

    test_nginx
    reload_or_start_nginx

    upsert_env_value "MUSIC_SHARE_PUBLIC_API_BASE_URL" "https://${domain}"
    upsert_env_value "MUSIC_SHARE_PUBLIC_SHARE_BASE_URL" "https://${domain}"
    upsert_env_value "MUSIC_SHARE_USE_X_ACCEL_REDIRECT" "true"
    upsert_env_value "MUSIC_SHARE_INTERNAL_MEDIA_PREFIX" "/internal-media"
    upsert_env_value "MUSIC_SHARE_DATA_ROOT" "${APP_DATA_ROOT}"

    log "setup complete"
    log "nginx config: ${NGINX_SITE_CONF}"
    log "certificate directory: ${LE_CONFIG_DIR}/live/${domain}"
    log "backend env file updated: ${ENV_FILE}"
    log "next step: run sudo bash ./start.sh from the project root"
}

main "$@"
