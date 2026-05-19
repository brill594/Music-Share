from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import re
import secrets
import shutil
import time
from collections import defaultdict, deque
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.parse import quote
from uuid import uuid4

from fastapi import HTTPException, Request, UploadFile, status
from fastapi.responses import FileResponse, Response

from .config import Settings
from .database import Database
from .models import SessionRecord, ShareRecord, ensure_utc, parse_datetime, to_iso, utcnow

CLIENT_INSTALL_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{8,128}$")
SAFE_FILENAME_PATTERN = re.compile(r"[^A-Za-z0-9._-]+")
SHARE_CODE_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"

MIME_TO_EXTENSION = {
    "audio/aac": ".aac",
    "audio/mp4": ".m4a",
    "audio/mpeg": ".mp3",
    "audio/ogg": ".ogg",
    "audio/x-m4a": ".m4a",
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
}


@dataclass(slots=True)
class ShareUrls:
    share_url: str
    track_url: str
    stream_url: str
    cover_url: str | None
    background_url: str | None


@dataclass(slots=True)
class GlobalBackgroundConfig:
    mime_type: str
    path: str
    updated_at: str

class AuthService:
    def __init__(self, settings: Settings, database: Database) -> None:
        self.settings = settings
        self.database = database

    def authenticate(self, password: str) -> tuple[SessionRecord, str]:
        normalized = password.strip()
        if hmac.compare_digest(normalized, self.settings.admin_password):
            role = "admin"
        elif hmac.compare_digest(normalized, self.settings.user_password):
            role = "user"
        else:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid password.",
            )

        raw_token = secrets.token_urlsafe(32)
        now = utcnow()
        session = SessionRecord(
            session_key_hash=self._hash_session_key(raw_token),
            role=role,
            auth_type="api_key",
            created_at=now,
            expires_at=now + timedelta(seconds=self.settings.session_ttl_seconds),
        )
        self.database.upsert_session(session)
        return session, raw_token

    def resolve_session(self, session_key: str) -> SessionRecord | None:
        token = session_key.strip()
        if not token:
            return None
        session = self.database.get_session(self._hash_session_key(token))
        if session is None:
            return None
        if session.expires_at <= utcnow():
            self.database.delete_expired_sessions(to_iso(utcnow()) or "")
            return None
        return session

    @staticmethod
    def _hash_session_key(session_key: str) -> str:
        return hashlib.sha256(session_key.encode("utf-8")).hexdigest()


class StorageService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.settings.storage_root.mkdir(parents=True, exist_ok=True)
        self.settings.storage_root.chmod(0o755)

    def create_share_record(
        self,
        *,
        database: Database,
        client_install_id: str,
        title: str,
        artist: str,
        album: str,
        duration_ms: int,
        audio_mime: str,
        client_created_at: datetime | None,
        expires_at: datetime,
    ) -> ShareRecord:
        share_uuid = str(uuid4())
        share_code = self._generate_share_code(database)
        audio_extension = extension_for_mime(audio_mime)
        return ShareRecord(
            uuid=share_uuid,
            share_code=share_code,
            client_install_id=client_install_id,
            title=title,
            artist=artist,
            album=album,
            duration_ms=duration_ms,
            audio_mime=audio_mime,
            audio_path=f"{share_uuid}/audio{audio_extension}",
            cover_mime=None,
            cover_path=None,
            created_at=utcnow(),
            client_created_at=client_created_at,
            expires_at=expires_at,
            terminated_at=None,
            status="active",
        )

    async def persist_uploads(
        self,
        share: ShareRecord,
        *,
        audio_upload: UploadFile,
        cover_upload: UploadFile | None,
    ) -> ShareRecord:
        share_dir = self.settings.storage_root / share.uuid
        share_dir.mkdir(parents=True, exist_ok=True)
        share_dir.chmod(0o755)
        audio_path = self.settings.storage_root / share.audio_path
        try:
            await self._write_upload(audio_upload, audio_path, self.settings.max_audio_upload_bytes)
            if cover_upload is not None:
                cover_mime = normalize_mime_type(
                    cover_upload.content_type,
                    allowed=self.settings.allowed_image_mime_types,
                    field_name="cover content_type",
                )
                cover_path = Path(share.uuid) / f"cover{extension_for_mime(cover_mime)}"
                await self._write_upload(
                    cover_upload,
                    self.settings.storage_root / cover_path,
                    self.settings.max_cover_upload_bytes,
                )
                share.cover_mime = cover_mime
                share.cover_path = cover_path.as_posix()
            self.write_metadata(share)
            return share
        except Exception:
            shutil.rmtree(share_dir, ignore_errors=True)
            raise

    def write_metadata(self, share: ShareRecord) -> None:
        meta_path = self.settings.storage_root / share.uuid / "meta.json"
        meta_path.write_text(
            json.dumps(share.metadata_payload(), ensure_ascii=True, indent=2),
            encoding="utf-8",
        )
        meta_path.chmod(0o600)

    def delete_share_assets(self, share: ShareRecord) -> None:
        shutil.rmtree(self.settings.storage_root / share.uuid, ignore_errors=True)

    def read_global_background_config(self) -> GlobalBackgroundConfig | None:
        manifest_path = self.settings.storage_root / "global-background.json"
        try:
            payload = json.loads(manifest_path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return None
        except json.JSONDecodeError:
            return None
        mime_type = str(payload.get("mime_type") or "")
        path = str(payload.get("path") or "")
        updated_at = str(payload.get("updated_at") or "")
        if not mime_type or not path or not updated_at:
            return None
        background_path = self.settings.storage_root / path
        if not background_path.is_file():
            return None
        return GlobalBackgroundConfig(mime_type=mime_type, path=path, updated_at=updated_at)

    async def stage_global_background(self, upload: UploadFile) -> GlobalBackgroundConfig:
        mime_type = normalize_mime_type(
            upload.content_type,
            allowed=self.settings.allowed_image_mime_types,
            field_name="background content_type",
        )
        extension = extension_for_mime(mime_type)
        background_dir = self.settings.storage_root / "global-background"
        background_dir.mkdir(parents=True, exist_ok=True)
        background_dir.chmod(0o755)
        relative_path = Path("global-background") / f"staged-{uuid4()}{extension}"
        destination = self.settings.storage_root / relative_path
        try:
            await self._write_upload(
                upload,
                destination,
                self.settings.max_cover_upload_bytes,
            )
        except Exception:
            destination.unlink(missing_ok=True)
            raise
        return GlobalBackgroundConfig(
            mime_type=mime_type,
            path=relative_path.as_posix(),
            updated_at=to_iso(utcnow()),
        )

    def publish_global_background(self, staged: GlobalBackgroundConfig) -> GlobalBackgroundConfig:
        old_config = self.read_global_background_config()
        staged_path = self.settings.storage_root / staged.path
        extension = staged_path.suffix
        final_relative_path = Path("global-background") / f"background-{uuid4()}{extension}"
        final_path = self.settings.storage_root / final_relative_path
        try:
            staged_path.rename(final_path)
            config = GlobalBackgroundConfig(
                mime_type=staged.mime_type,
                path=final_relative_path.as_posix(),
                updated_at=staged.updated_at,
            )
            manifest_path = self.settings.storage_root / "global-background.json"
            manifest_tmp_path = self.settings.storage_root / f"global-background.{uuid4()}.tmp"
            manifest_tmp_path.write_text(
                json.dumps(
                    {
                        "mime_type": config.mime_type,
                        "path": config.path,
                        "updated_at": config.updated_at,
                    },
                    ensure_ascii=True,
                    indent=2,
                ),
                encoding="utf-8",
            )
            manifest_tmp_path.chmod(0o600)
            manifest_tmp_path.replace(manifest_path)
        except Exception:
            final_path.unlink(missing_ok=True)
            raise
        if old_config is not None and old_config.path != config.path:
            (self.settings.storage_root / old_config.path).unlink(missing_ok=True)
        return config

    def global_background_response(self) -> FileResponse:
        config = self.read_global_background_config()
        if config is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Background not configured.",
            )
        return FileResponse(
            self.settings.storage_root / config.path,
            media_type=config.mime_type,
        )

    def delete_global_background(self, config: GlobalBackgroundConfig) -> None:
        (self.settings.storage_root / config.path).unlink(missing_ok=True)
        current = self.read_global_background_config()
        if current is not None and current.path == config.path:
            (self.settings.storage_root / "global-background.json").unlink(missing_ok=True)

    def audio_response(self, share: ShareRecord) -> Response:
        audio_path = self.settings.storage_root / share.audio_path
        filename = build_download_filename(share)
        if self.settings.use_x_accel_redirect:
            headers = {
                "X-Accel-Redirect": self.internal_audio_path(share),
                "Content-Disposition": build_content_disposition(filename),
                "Cache-Control": "private, max-age=60",
            }
            return Response(status_code=200, headers=headers, media_type=share.audio_mime)
        return FileResponse(
            audio_path,
            media_type=share.audio_mime,
            filename=filename,
            content_disposition_type="inline",
        )

    def cover_response(self, share: ShareRecord) -> FileResponse:
        if share.cover_path is None or share.cover_mime is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Cover not found.",
            )
        return FileResponse(
            self.settings.storage_root / share.cover_path,
            media_type=share.cover_mime,
        )

    def internal_audio_path(self, share: ShareRecord) -> str:
        prefix = self.settings.internal_media_prefix.rstrip("/")
        return f"{prefix}/{share.audio_path}"

    @staticmethod
    def _generate_share_code(database: Database) -> str:
        while True:
            share_code = "".join(secrets.choice(SHARE_CODE_ALPHABET) for _ in range(16))
            if not database.share_code_exists(share_code):
                return share_code

    @staticmethod
    async def _write_upload(
        upload: UploadFile,
        destination: Path,
        max_bytes: int,
    ) -> None:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.parent.chmod(0o755)
        written = 0
        await upload.seek(0)
        with destination.open("wb") as handle:
            while True:
                chunk = await upload.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > max_bytes:
                    raise HTTPException(
                        status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                        detail=f"Upload exceeds {max_bytes} bytes.",
                    )
                handle.write(chunk)
        destination.chmod(0o644)
        await upload.close()


class RateLimiter:
    def __init__(self) -> None:
        self._buckets: dict[str, deque[float]] = defaultdict(deque)

    def enforce(self, *, bucket: str, key: str, limit: int, window_seconds: int) -> None:
        now = time.monotonic()
        bucket_key = f"{bucket}:{key}"
        hits = self._buckets[bucket_key]
        while hits and now - hits[0] > window_seconds:
            hits.popleft()
        if len(hits) >= limit:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="Too many requests.",
            )
        hits.append(now)


class CleanupService:
    def __init__(
        self,
        settings: Settings,
        database: Database,
        storage: StorageService,
    ) -> None:
        self.settings = settings
        self.database = database
        self.storage = storage
        self._stop_event = asyncio.Event()
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run_loop())

    async def stop(self) -> None:
        self._stop_event.set()
        if self._task is not None:
            await self._task
            self._task = None
        self._stop_event = asyncio.Event()

    def run_once(self) -> None:
        now_iso = to_iso(utcnow()) or ""
        for share in self.database.list_cleanup_candidates(now_iso):
            self.storage.delete_share_assets(share)
            self.database.delete_share(share.uuid)
        self.database.delete_expired_sessions(now_iso)

    async def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            self.run_once()
            try:
                await asyncio.wait_for(
                    self._stop_event.wait(),
                    timeout=self.settings.cleanup_interval_seconds,
                )
            except TimeoutError:
                continue


def build_share_urls(
    settings: Settings,
    request: Request,
    share: ShareRecord,
    *,
    include_global_background: bool = False,
) -> ShareUrls:
    api_base = resolve_public_base_url(settings.public_api_base_url, request)
    share_base = resolve_public_base_url(settings.public_share_base_url, request)
    return ShareUrls(
        share_url=f"{share_base}/{share.share_code}",
        track_url=f"{api_base}/track/{share.share_code}",
        stream_url=f"{api_base}/stream/{share.share_code}",
        cover_url=f"{api_base}/cover/{share.share_code}" if share.cover_path else None,
        background_url=f"{api_base}/background" if include_global_background else None,
    )


def serialize_share_public(
    settings: Settings,
    request: Request,
    share: ShareRecord,
    *,
    include_global_background: bool = False,
) -> dict[str, Any]:
    urls = build_share_urls(settings, request, share, include_global_background=include_global_background)
    return {
        "share_code": share.share_code,
        "title": share.title,
        "artist": share.artist,
        "album": share.album,
        "duration_ms": share.duration_ms,
        "audio_mime": share.audio_mime,
        "share_url": urls.share_url,
        "track_url": urls.track_url,
        "stream_url": urls.stream_url,
        "cover_url": urls.cover_url,
        "background_url": urls.background_url,
        "created_at": to_iso(share.created_at),
        "expires_at": to_iso(share.expires_at),
        "status": share.effective_status(),
    }


def serialize_share_upload(
    settings: Settings,
    request: Request,
    share: ShareRecord,
    *,
    include_global_background: bool = False,
) -> dict[str, Any]:
    payload = serialize_share_public(settings, request, share, include_global_background=include_global_background)
    payload["uuid"] = share.uuid
    return payload


def serialize_share_management(
    settings: Settings,
    request: Request,
    share: ShareRecord,
    *,
    include_client_install_id: bool = False,
    include_global_background: bool = False,
) -> dict[str, Any]:
    payload = serialize_share_public(settings, request, share, include_global_background=include_global_background)
    payload.update(
        {
            "client_install_id": share.client_install_id if include_client_install_id else None,
            "client_created_at": to_iso(share.client_created_at),
            "terminated_at": to_iso(share.terminated_at),
            "remaining_seconds": share.remaining_seconds(),
        }
    )
    if not include_client_install_id:
        payload.pop("client_install_id")
    return payload

def serialize_global_background(
    settings: Settings,
    request: Request,
    config: GlobalBackgroundConfig | None,
) -> dict[str, Any]:
    api_base = resolve_public_base_url(settings.public_api_base_url, request)
    return {
        "configured": config is not None,
        "background_url": f"{api_base}/background" if config else None,
        "updated_at": config.updated_at if config else None,
    }


def serialize_usage_summary(
    settings: Settings,
    config: dict[str, Any] | None = None,
    counters: dict[str, Any] | None = None,
    storage_live_bytes: int | None = None,
) -> dict[str, Any]:
    now = to_iso(utcnow())
    if storage_live_bytes is None:
        storage_root = settings.storage_root
        live_bytes = 0
        if storage_root.exists():
            live_bytes = sum(path.stat().st_size for path in storage_root.rglob("*") if path.is_file())
    else:
        live_bytes = storage_live_bytes
    used_gb = live_bytes / 1_000_000_000
    config = config or {}
    counters = counters or {}
    enabled = bool(config.get("enabled", True))
    updated_at = config.get("updated_at") if isinstance(config.get("updated_at"), str) else None
    d1_rows_read_daily_limit = int(config.get("d1_rows_read_daily_limit", 5_000_000))
    d1_rows_written_daily_limit = int(config.get("d1_rows_written_daily_limit", 100_000))
    storage_limit_gb = float(config.get("d1_storage_gb_limit", 5.0))
    r2_class_a_rolling_30d_limit = int(config.get("r2_class_a_rolling_30d_limit", 1_000_000))
    r2_class_b_rolling_30d_limit = int(config.get("r2_class_b_rolling_30d_limit", 10_000_000))
    r2_limit_gb_month = float(config.get("r2_storage_gb_month_limit", 10.0))
    d1_rows_read_daily = int(counters.get("d1_rows_read_daily", 0))
    d1_rows_written_daily = int(counters.get("d1_rows_written_daily", 0))
    r2_class_a_rolling_30d = int(counters.get("r2_class_a_rolling_30d", 0))
    r2_class_b_rolling_30d = int(counters.get("r2_class_b_rolling_30d", 0))
    return {
        "enabled": enabled,
        "updated_at": updated_at,
        "generated_at": now,
        "cloudflare_reference": {
            "rolling_window_days": 30,
            "d1_rows_read_daily_limit": d1_rows_read_daily_limit,
            "d1_rows_written_daily_limit": d1_rows_written_daily_limit,
            "d1_storage_gb_limit": storage_limit_gb,
            "r2_class_a_rolling_30d_limit": r2_class_a_rolling_30d_limit,
            "r2_class_b_rolling_30d_limit": r2_class_b_rolling_30d_limit,
            "r2_storage_gb_month_limit": r2_limit_gb_month,
        },
        "d1_rows_read_daily": {
            "used": d1_rows_read_daily,
            "limit": d1_rows_read_daily_limit,
            "exceeded": d1_rows_read_daily > d1_rows_read_daily_limit,
        },
        "d1_rows_written_daily": {
            "used": d1_rows_written_daily,
            "limit": d1_rows_written_daily_limit,
            "exceeded": d1_rows_written_daily > d1_rows_written_daily_limit,
        },
        "d1_storage": {
            "used_bytes": live_bytes,
            "used_gb": used_gb,
            "limit_gb": storage_limit_gb,
            "exceeded": used_gb > storage_limit_gb,
        },
        "r2_class_a_rolling_30d": {
            "used": r2_class_a_rolling_30d,
            "limit": r2_class_a_rolling_30d_limit,
            "exceeded": r2_class_a_rolling_30d > r2_class_a_rolling_30d_limit,
        },
        "r2_class_b_rolling_30d": {
            "used": r2_class_b_rolling_30d,
            "limit": r2_class_b_rolling_30d_limit,
            "exceeded": r2_class_b_rolling_30d > r2_class_b_rolling_30d_limit,
        },
        "r2_storage_rolling_30d": {
            "used_gb_month": used_gb,
            "limit_gb_month": r2_limit_gb_month,
            "live_bytes": live_bytes,
            "exceeded": used_gb > r2_limit_gb_month,
        },
    }


def require_share_available(share: ShareRecord | None) -> ShareRecord:
    if share is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Share not found.",
        )
    current_status = share.effective_status()
    if current_status != "active":
        raise HTTPException(
            status_code=status.HTTP_410_GONE,
            detail=f"Share is {current_status}.",
        )
    return share


def extract_session_key(request: Request, cookie_name: str) -> str | None:
    direct = request.headers.get("X-Session-Key")
    if direct:
        return direct
    authorization = request.headers.get("Authorization")
    if authorization and authorization.startswith("Bearer "):
        return authorization.removeprefix("Bearer ").strip()
    cookie = request.cookies.get(cookie_name)
    if cookie:
        return cookie
    return None


def require_client_install_id(request: Request) -> str:
    value = (request.headers.get("X-Client-Install-Id") or "").strip()
    if not CLIENT_INSTALL_ID_PATTERN.fullmatch(value):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Missing or invalid X-Client-Install-Id header.",
        )
    return value


def normalize_mime_type(
    value: str | None,
    *,
    allowed: frozenset[str],
    field_name: str,
) -> str:
    normalized = (value or "").strip().lower()
    if normalized not in allowed:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{field_name} is not allowed.",
        )
    return normalized


def validate_text(value: str, *, field_name: str, max_length: int, required: bool) -> str:
    normalized = value.strip()
    if required and not normalized:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{field_name} is required.",
        )
    if len(normalized) > max_length:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{field_name} is too long.",
        )
    return normalized


def parse_optional_datetime(value: str | None, *, field_name: str) -> datetime | None:
    if value is None or not value.strip():
        return None
    try:
        return parse_datetime(value)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{field_name} must be ISO 8601.",
        ) from exc


def resolve_expiration(
    *,
    settings: Settings,
    expire_after_seconds: int | None,
    expire_at: str | None,
) -> datetime:
    if expire_after_seconds is not None and expire_at:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Only one of expire_after_seconds or expire_at may be provided.",
        )
    now = utcnow()
    if expire_after_seconds is not None:
        if expire_after_seconds <= 0 or expire_after_seconds > settings.share_max_ttl_seconds:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="expire_after_seconds is outside the allowed range.",
            )
        return now + timedelta(seconds=expire_after_seconds)
    if expire_at:
        parsed = parse_optional_datetime(expire_at, field_name="expire_at")
        assert parsed is not None
        if parsed <= now:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="expire_at must be in the future.",
            )
        if parsed > now + timedelta(seconds=settings.share_max_ttl_seconds):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="expire_at exceeds the allowed lifetime.",
            )
        return parsed
    return now + timedelta(seconds=settings.share_default_ttl_seconds)


def validate_duration(duration_ms: int, max_duration_ms: int) -> int:
    if duration_ms <= 0 or duration_ms > max_duration_ms:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="duration_ms is outside the allowed range.",
        )
    return duration_ms


def resolve_public_base_url(configured: str | None, request: Request) -> str:
    base = configured or str(request.base_url)
    return base.rstrip("/")


def extension_for_mime(mime_type: str) -> str:
    return MIME_TO_EXTENSION.get(mime_type, "")


def build_download_filename(share: ShareRecord) -> str:
    extension = extension_for_mime(share.audio_mime)
    raw = SAFE_FILENAME_PATTERN.sub("_", share.title).strip("._")
    base = raw or share.share_code
    return f"{base}{extension}"


def build_content_disposition(filename: str) -> str:
    return f"inline; filename*=UTF-8''{quote(filename)}"
