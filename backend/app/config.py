from __future__ import annotations

import json
import logging
import os
import secrets
from dataclasses import dataclass, field
from pathlib import Path

from .models import utcnow, to_iso

LOGGER = logging.getLogger("music_share.backend")

BASE_DIR = Path(__file__).resolve().parent.parent


def _chmod_if_possible(path: Path, mode: int) -> None:
    try:
        path.chmod(mode)
    except FileNotFoundError:
        return
    except PermissionError:
        LOGGER.warning("Unable to set mode %s on %s", oct(mode), path)


def _get_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _get_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    return int(raw)


def _get_optional_str(name: str) -> str | None:
    raw = os.getenv(name)
    if raw is None:
        return None
    value = raw.strip()
    return value or None


def _get_csv(name: str) -> tuple[str, ...]:
    raw = os.getenv(name)
    if raw is None:
        return ()
    return tuple(value.strip().rstrip("/") for value in raw.split(",") if value.strip())


@dataclass(slots=True)
class Settings:
    data_root: Path
    database_path: Path
    storage_root: Path
    runtime_secret_path: Path
    user_password: str
    admin_password: str
    session_ttl_seconds: int = 86_400
    share_default_ttl_seconds: int = 86_400
    share_max_ttl_seconds: int = 2_592_000
    cleanup_interval_seconds: int = 60
    max_audio_upload_bytes: int = 200 * 1024 * 1024
    max_cover_upload_bytes: int = 8 * 1024 * 1024
    max_duration_ms: int = 43_200_000
    public_api_base_url: str | None = None
    public_share_base_url: str | None = None
    cors_allowed_origins: tuple[str, ...] = ()
    internal_media_prefix: str = "/internal-media"
    use_x_accel_redirect: bool = True
    session_cookie_name: str = "music_share_session"
    login_rate_limit: int = 10
    login_rate_window_seconds: int = 60
    upload_rate_limit: int = 30
    upload_rate_window_seconds: int = 3_600
    public_rate_limit: int = 240
    public_rate_window_seconds: int = 60
    allowed_audio_mime_types: frozenset[str] = field(
        default_factory=lambda: frozenset(
            {
                "audio/aac",
                "audio/mp4",
                "audio/mpeg",
                "audio/ogg",
                "audio/x-m4a",
            }
        )
    )
    allowed_image_mime_types: frozenset[str] = field(
        default_factory=lambda: frozenset(
            {
                "image/jpeg",
                "image/png",
                "image/webp",
            }
        )
    )

    def ensure_paths(self) -> None:
        self.data_root.mkdir(parents=True, exist_ok=True)
        self.storage_root.mkdir(parents=True, exist_ok=True)
        _chmod_if_possible(self.data_root, 0o751)
        _chmod_if_possible(self.storage_root, 0o755)


def _read_runtime_secrets(path: Path) -> dict[str, object]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def load_settings() -> Settings:
    data_root = Path(
        _get_optional_str("MUSIC_SHARE_DATA_ROOT") or BASE_DIR / "data"
    ).expanduser().resolve()
    runtime_secret_path = Path(
        _get_optional_str("MUSIC_SHARE_RUNTIME_SECRET_PATH")
        or data_root / "runtime-secrets.json"
    ).expanduser().resolve()
    runtime_secrets = _read_runtime_secrets(runtime_secret_path)
    user_password_from_env = _get_optional_str("MUSIC_SHARE_USER_PASSWORD")
    admin_password_from_env = _get_optional_str("MUSIC_SHARE_ADMIN_PASSWORD")
    user_password = user_password_from_env or str(runtime_secrets.get("user_password") or secrets.token_urlsafe(18))
    admin_password = admin_password_from_env or str(runtime_secrets.get("admin_password") or secrets.token_urlsafe(18))
    settings = Settings(
        data_root=data_root,
        database_path=Path(
            _get_optional_str("MUSIC_SHARE_DATABASE_PATH")
            or data_root / "music_share.sqlite3"
        ).expanduser().resolve(),
        storage_root=Path(
            _get_optional_str("MUSIC_SHARE_STORAGE_ROOT") or data_root / "storage"
        ).expanduser().resolve(),
        runtime_secret_path=runtime_secret_path,
        user_password=user_password,
        admin_password=admin_password,
        session_ttl_seconds=_get_int("MUSIC_SHARE_SESSION_TTL_SECONDS", 86_400),
        share_default_ttl_seconds=_get_int(
            "MUSIC_SHARE_SHARE_DEFAULT_TTL_SECONDS", 86_400
        ),
        share_max_ttl_seconds=_get_int(
            "MUSIC_SHARE_SHARE_MAX_TTL_SECONDS", 2_592_000
        ),
        cleanup_interval_seconds=_get_int(
            "MUSIC_SHARE_CLEANUP_INTERVAL_SECONDS", 60
        ),
        max_audio_upload_bytes=_get_int(
            "MUSIC_SHARE_MAX_AUDIO_UPLOAD_BYTES", 200 * 1024 * 1024
        ),
        max_cover_upload_bytes=_get_int(
            "MUSIC_SHARE_MAX_COVER_UPLOAD_BYTES", 8 * 1024 * 1024
        ),
        max_duration_ms=_get_int("MUSIC_SHARE_MAX_DURATION_MS", 43_200_000),
        public_api_base_url=_get_optional_str("MUSIC_SHARE_PUBLIC_API_BASE_URL"),
        public_share_base_url=_get_optional_str("MUSIC_SHARE_PUBLIC_SHARE_BASE_URL"),
        cors_allowed_origins=_get_csv("MUSIC_SHARE_CORS_ALLOWED_ORIGINS"),
        internal_media_prefix=_get_optional_str("MUSIC_SHARE_INTERNAL_MEDIA_PREFIX")
        or "/internal-media",
        use_x_accel_redirect=_get_bool("MUSIC_SHARE_USE_X_ACCEL_REDIRECT", True),
        session_cookie_name=_get_optional_str("MUSIC_SHARE_SESSION_COOKIE_NAME")
        or "music_share_session",
        login_rate_limit=_get_int("MUSIC_SHARE_LOGIN_RATE_LIMIT", 10),
        login_rate_window_seconds=_get_int(
            "MUSIC_SHARE_LOGIN_RATE_WINDOW_SECONDS", 60
        ),
        upload_rate_limit=_get_int("MUSIC_SHARE_UPLOAD_RATE_LIMIT", 30),
        upload_rate_window_seconds=_get_int(
            "MUSIC_SHARE_UPLOAD_RATE_WINDOW_SECONDS", 3_600
        ),
        public_rate_limit=_get_int("MUSIC_SHARE_PUBLIC_RATE_LIMIT", 240),
        public_rate_window_seconds=_get_int(
            "MUSIC_SHARE_PUBLIC_RATE_WINDOW_SECONDS", 60
        ),
    )
    settings.ensure_paths()
    if user_password_from_env is None or admin_password_from_env is None:
        _write_runtime_secrets(
            settings,
            include_user_password=user_password_from_env is None,
            include_admin_password=admin_password_from_env is None,
        )
    return settings


def _write_runtime_secrets(
    settings: Settings,
    *,
    include_user_password: bool,
    include_admin_password: bool,
) -> None:
    payload: dict[str, object] = {
        "generated_at": to_iso(utcnow()),
        "session_ttl_seconds": settings.session_ttl_seconds,
    }
    if include_user_password:
        payload["user_password"] = settings.user_password
    if include_admin_password:
        payload["admin_password"] = settings.admin_password
    settings.runtime_secret_path.parent.mkdir(parents=True, exist_ok=True)
    settings.runtime_secret_path.write_text(
        json.dumps(payload, ensure_ascii=True, indent=2),
        encoding="utf-8",
    )
    _chmod_if_possible(settings.runtime_secret_path, 0o600)
    LOGGER.warning(
        "Generated runtime passwords written to %s. Move them to environment variables before production.",
        settings.runtime_secret_path,
    )
