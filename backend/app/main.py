from __future__ import annotations

import logging
import json
from contextlib import asynccontextmanager
from datetime import date, timedelta

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .config import Settings, load_settings
from .database import Database
from .models import ShareRecord, to_iso, utcnow
from .services import (
    AuthService,
    CleanupService,
    RateLimiter,
    StorageService,
    extract_session_key,
    normalize_mime_type,
    parse_optional_datetime,
    require_client_install_id,
    require_share_available,
    resolve_expiration,
    serialize_share_management,
    serialize_share_public,
    serialize_share_upload,
    serialize_global_background,
    serialize_usage_summary,
    validate_duration,
    validate_text,
)

LOGGER = logging.getLogger("music_share.backend")


class LoginRequest(BaseModel):
    password: str = Field(min_length=1, max_length=256)


class UsageLimitUpdateRequest(BaseModel):
    enabled: bool = True
    d1_rows_read_daily_limit: int = Field(gt=0)
    d1_rows_written_daily_limit: int = Field(gt=0)
    d1_storage_gb_limit: float = Field(gt=0)
    r2_class_a_rolling_30d_limit: int = Field(gt=0)
    r2_class_b_rolling_30d_limit: int = Field(gt=0)
    r2_storage_gb_month_limit: float = Field(gt=0)




def resolve_cors_allowed_origins(settings: Settings) -> list[str]:
    origins = {origin.rstrip("/") for origin in settings.cors_allowed_origins if origin}
    if settings.public_share_base_url:
        origins.add(settings.public_share_base_url.rstrip("/"))
    origins.update(
        {
            "http://localhost:5173",
            "http://127.0.0.1:5173",
        }
    )
    return sorted(origins)

def create_app(settings: Settings | None = None) -> FastAPI:
    resolved_settings = settings or load_settings()
    database = Database(resolved_settings.database_path)
    database.initialize()
    auth_service = AuthService(resolved_settings, database)
    storage_service = StorageService(resolved_settings)
    cleanup_service = CleanupService(resolved_settings, database, storage_service)
    rate_limiter = RateLimiter()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        LOGGER.info("Music Share backend starting with storage=%s", resolved_settings.storage_root)
        await cleanup_service.start()
        try:
            yield
        finally:
            await cleanup_service.stop()

    app = FastAPI(title="Music Share Backend", version="0.1.0", lifespan=lifespan)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=resolve_cors_allowed_origins(resolved_settings),
        allow_credentials=True,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-Client-Install-Id", "X-Session-Key"],
    )
    app.state.settings = resolved_settings
    app.state.database = database
    app.state.auth_service = auth_service
    app.state.storage_service = storage_service
    app.state.cleanup_service = cleanup_service
    app.state.rate_limiter = rate_limiter

    def enforce_limit(request: Request, *, bucket: str, limit: int, window_seconds: int) -> None:
        client_host = request.client.host if request.client else "unknown"
        rate_limiter.enforce(
            bucket=bucket,
            key=client_host,
            limit=limit,
            window_seconds=window_seconds,
        )

    def get_authenticated_session(request: Request, *, admin_only: bool = False):
        session_key = extract_session_key(request, resolved_settings.session_cookie_name)
        session = auth_service.resolve_session(session_key or "")
        if session is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Missing or invalid session.",
            )
        if admin_only and session.role != "admin":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Admin session required.",
            )
        return session

    def get_share_or_404(share_code: str) -> ShareRecord:
        share = database.get_share_by_code(share_code)
        if share is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Share not found.",
            )
        return share

    usage_config_path = resolved_settings.data_root / "usage-limits.json"

    def read_usage_config() -> dict:
        try:
            payload = json.loads(usage_config_path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {}
        except json.JSONDecodeError:
            return {}
        return payload if isinstance(payload, dict) else {}

    def write_usage_config(payload: UsageLimitUpdateRequest) -> dict:
        updated = payload.model_dump()
        updated["updated_at"] = to_iso(utcnow())
        usage_config_path.parent.mkdir(parents=True, exist_ok=True)
        usage_config_path.write_text(json.dumps(updated, ensure_ascii=True, indent=2), encoding="utf-8")
        usage_config_path.chmod(0o600)
        return updated

    usage_counters_path = resolved_settings.data_root / "usage-counters.json"

    def usage_day_key() -> str:
        return utcnow().date().isoformat()

    def read_usage_counter_days() -> dict[str, dict]:
        today = utcnow().date()
        cutoff = today - timedelta(days=29)
        try:
            payload = json.loads(usage_counters_path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {}
        except json.JSONDecodeError:
            return {}
        raw_days = payload.get("days") if isinstance(payload, dict) else None
        if not isinstance(raw_days, dict):
            return {}
        days: dict[str, dict] = {}
        for day, counters in raw_days.items():
            if not isinstance(day, str) or not isinstance(counters, dict):
                continue
            try:
                parsed_day = date.fromisoformat(day)
            except ValueError:
                continue
            if cutoff <= parsed_day <= today:
                days[day] = counters
        return days

    def write_usage_counter_days(days: dict[str, dict]) -> None:
        usage_counters_path.parent.mkdir(parents=True, exist_ok=True)
        usage_counters_path.write_text(json.dumps({"days": days}, ensure_ascii=True, indent=2), encoding="utf-8")
        usage_counters_path.chmod(0o600)

    def aggregate_usage_counters(days: dict[str, dict]) -> dict:
        today_bucket = days.get(usage_day_key(), {})
        return {
            "d1_rows_read_daily": int(today_bucket.get("d1_rows_read_daily", 0)),
            "d1_rows_written_daily": int(today_bucket.get("d1_rows_written_daily", 0)),
            "r2_class_a_rolling_30d": sum(int(day.get("r2_class_a_rolling_30d", 0)) for day in days.values()),
            "r2_class_b_rolling_30d": sum(int(day.get("r2_class_b_rolling_30d", 0)) for day in days.values()),
        }

    def read_usage_counters() -> dict:
        return aggregate_usage_counters(read_usage_counter_days())

    def usage_counters_with_delta(**delta: int) -> dict:
        next_counters = dict(read_usage_counters())
        for key, value in delta.items():
            next_counters[key] = int(next_counters.get(key, 0)) + value
        return next_counters

    def current_storage_bytes() -> int:
        storage_root = resolved_settings.storage_root
        if not storage_root.exists():
            return 0
        return sum(path.stat().st_size for path in storage_root.rglob("*") if path.is_file())

    def request_content_length(request: Request) -> int:
        try:
            return max(0, int(request.headers.get("content-length") or "0"))
        except ValueError:
            return 0

    def enforce_usage_guardrails(
        check_storage: bool = False,
        extra_storage_bytes: int = 0,
        **delta: int,
    ) -> None:
        config = read_usage_config()
        if not bool(config.get("enabled", True)):
            return
        storage_live_bytes = current_storage_bytes() + extra_storage_bytes if check_storage else 0
        summary = serialize_usage_summary(
            resolved_settings,
            config,
            usage_counters_with_delta(**delta),
            storage_live_bytes,
        )
        exceeded = [
            name
            for name in (
                "d1_rows_read_daily",
                "d1_rows_written_daily",
                "d1_storage",
                "r2_class_a_rolling_30d",
                "r2_class_b_rolling_30d",
                "r2_storage_rolling_30d",
            )
            if summary[name]["exceeded"]
        ]
        if exceeded:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=f"Usage limit exceeded: {', '.join(exceeded)}.",
            )

    def record_usage(**delta: int) -> None:
        days = read_usage_counter_days()
        today_bucket = dict(days.get(usage_day_key(), {}))
        for key, value in delta.items():
            today_bucket[key] = int(today_bucket.get(key, 0)) + value
        days[usage_day_key()] = today_bucket
        write_usage_counter_days(days)

    @app.post("/auth/login")
    async def login(payload: LoginRequest, request: Request) -> JSONResponse:
        enforce_limit(
            request,
            bucket="login",
            limit=resolved_settings.login_rate_limit,
            window_seconds=resolved_settings.login_rate_window_seconds,
        )
        session, raw_token = auth_service.authenticate(payload.password)
        body = {
            "role": session.role,
            "auth_type": session.auth_type,
            "session_key": raw_token,
            "expires_at": to_iso(session.expires_at),
        }
        response = JSONResponse(body)
        response.set_cookie(
            key=resolved_settings.session_cookie_name,
            value=raw_token,
            httponly=True,
            secure=(
                (resolved_settings.public_api_base_url or str(request.base_url)).startswith("https://")
            ),
            samesite="lax",
            max_age=resolved_settings.session_ttl_seconds,
        )
        return response

    @app.post("/upload")
    async def upload(
        request: Request,
        file: UploadFile = File(...),
        cover: UploadFile | None = File(default=None),
        title: str = Form(...),
        artist: str = Form(default=""),
        album: str = Form(default=""),
        duration_ms: int = Form(...),
        audio_mime: str = Form(...),
        client_created_at: str | None = Form(default=None),
        expire_after_seconds: int | None = Form(default=None),
        expire_at: str | None = Form(default=None),
    ) -> dict:
        enforce_limit(
            request,
            bucket="upload",
            limit=resolved_settings.upload_rate_limit,
            window_seconds=resolved_settings.upload_rate_window_seconds,
        )
        get_authenticated_session(request)
        client_install_id = require_client_install_id(request)
        enforce_usage_guardrails(
            check_storage=True,
            extra_storage_bytes=request_content_length(request),
            d1_rows_written_daily=1,
            r2_class_a_rolling_30d=1,
        )
        normalized_title = validate_text(title, field_name="title", max_length=256, required=True)
        normalized_artist = validate_text(artist, field_name="artist", max_length=256, required=False)
        normalized_album = validate_text(album, field_name="album", max_length=256, required=False)
        normalized_audio_mime = normalize_mime_type(
            audio_mime,
            allowed=resolved_settings.allowed_audio_mime_types,
            field_name="audio_mime",
        )
        normalized_duration = validate_duration(duration_ms, resolved_settings.max_duration_ms)
        created_at = parse_optional_datetime(client_created_at, field_name="client_created_at")
        expires_at_value = resolve_expiration(
            settings=resolved_settings,
            expire_after_seconds=expire_after_seconds,
            expire_at=expire_at,
        )
        share = storage_service.create_share_record(
            database=database,
            client_install_id=client_install_id,
            title=normalized_title,
            artist=normalized_artist,
            album=normalized_album,
            duration_ms=normalized_duration,
            audio_mime=normalized_audio_mime,
            client_created_at=created_at,
            expires_at=expires_at_value,
        )
        persisted = await storage_service.persist_uploads(
            share,
            audio_upload=file,
            cover_upload=cover,
        )
        try:
            enforce_usage_guardrails(check_storage=True, d1_rows_written_daily=1, r2_class_a_rolling_30d=1)
        except Exception:
            storage_service.delete_share_assets(persisted)
            raise
        try:
            database.insert_share(persisted)
        except Exception:
            storage_service.delete_share_assets(persisted)
            raise
        record_usage(d1_rows_written_daily=1, r2_class_a_rolling_30d=1)
        payload = serialize_share_upload(
            resolved_settings,
            request,
            persisted,
            include_global_background=storage_service.read_global_background_config() is not None,
        )
        payload["status"] = "active"
        return payload

    @app.get("/track/{share_code}")
    async def track(share_code: str, request: Request) -> dict:
        enforce_limit(
            request,
            bucket="track",
            limit=resolved_settings.public_rate_limit,
            window_seconds=resolved_settings.public_rate_window_seconds,
        )
        enforce_usage_guardrails(d1_rows_read_daily=1)
        share = require_share_available(database.get_share_by_code(share_code))
        record_usage(d1_rows_read_daily=1)
        return serialize_share_public(
            resolved_settings,
            request,
            share,
            include_global_background=storage_service.read_global_background_config() is not None,
        )

    @app.get("/stream/{share_code}")
    async def stream(share_code: str, request: Request):
        enforce_limit(
            request,
            bucket="stream",
            limit=resolved_settings.public_rate_limit,
            window_seconds=resolved_settings.public_rate_window_seconds,
        )
        enforce_usage_guardrails(d1_rows_read_daily=1, r2_class_b_rolling_30d=1)
        share = require_share_available(database.get_share_by_code(share_code))
        record_usage(d1_rows_read_daily=1, r2_class_b_rolling_30d=1)
        return storage_service.audio_response(share)

    @app.get("/cover/{share_code}")
    async def cover_route(share_code: str, request: Request):
        enforce_limit(
            request,
            bucket="cover",
            limit=resolved_settings.public_rate_limit,
            window_seconds=resolved_settings.public_rate_window_seconds,
        )
        enforce_usage_guardrails(d1_rows_read_daily=1, r2_class_b_rolling_30d=1)
        share = get_share_or_404(share_code)
        if share.effective_status() != "active":
            raise HTTPException(
                status_code=status.HTTP_410_GONE,
                detail=f"Share is {share.effective_status()}.",
            )
        record_usage(d1_rows_read_daily=1, r2_class_b_rolling_30d=1)
        return storage_service.cover_response(share)

    @app.get("/background")
    async def background_route(request: Request):
        enforce_limit(
            request,
            bucket="background",
            limit=resolved_settings.public_rate_limit,
            window_seconds=resolved_settings.public_rate_window_seconds,
        )
        enforce_usage_guardrails(r2_class_b_rolling_30d=1)
        response = storage_service.global_background_response()
        record_usage(r2_class_b_rolling_30d=1)
        return response

    @app.get("/client/shares")
    async def list_client_shares(request: Request) -> dict:
        get_authenticated_session(request)
        client_install_id = require_client_install_id(request)
        include_global_background = storage_service.read_global_background_config() is not None
        items = [
            serialize_share_management(
                resolved_settings,
                request,
                share,
                include_global_background=include_global_background,
            )
            for share in database.list_shares_by_client(client_install_id)
        ]
        return {
            "items": items,
            "count": len(items),
            "generated_at": to_iso(utcnow()),
        }

    @app.get("/client/shares/{share_code}")
    async def get_client_share(share_code: str, request: Request) -> dict:
        get_authenticated_session(request)
        client_install_id = require_client_install_id(request)
        share = get_share_or_404(share_code)
        if share.client_install_id != client_install_id:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Share not found.",
            )
        return serialize_share_management(
            resolved_settings,
            request,
            share,
            include_global_background=storage_service.read_global_background_config() is not None,
        )

    @app.post("/client/shares/{share_code}/terminate")
    async def terminate_client_share(share_code: str, request: Request) -> dict:
        get_authenticated_session(request)
        client_install_id = require_client_install_id(request)
        share = get_share_or_404(share_code)
        if share.client_install_id != client_install_id:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Share not found.",
            )
        database.terminate_share(share_code, utcnow().isoformat())
        updated = get_share_or_404(share_code)
        storage_service.write_metadata(updated)
        return serialize_share_management(
            resolved_settings,
            request,
            updated,
            include_global_background=storage_service.read_global_background_config() is not None,
        )

    @app.get("/admin/tracks")
    async def list_admin_tracks(request: Request) -> dict:
        get_authenticated_session(request, admin_only=True)
        include_global_background = storage_service.read_global_background_config() is not None
        items = [
            serialize_share_management(
                resolved_settings,
                request,
                share,
                include_client_install_id=True,
                include_global_background=include_global_background,
            )
            for share in database.list_all_shares()
        ]
        return {
            "items": items,
            "count": len(items),
            "generated_at": to_iso(utcnow()),
        }

    @app.post("/admin/tracks/{share_code}/terminate")
    async def terminate_admin_track(share_code: str, request: Request) -> dict:
        get_authenticated_session(request, admin_only=True)
        share = get_share_or_404(share_code)
        database.terminate_share(share_code, utcnow().isoformat())
        updated = get_share_or_404(share_code)
        storage_service.write_metadata(updated)
        return serialize_share_management(
            resolved_settings,
            request,
            updated,
            include_client_install_id=True,
            include_global_background=storage_service.read_global_background_config() is not None,
        )

    @app.get("/admin/background")
    async def get_admin_background(request: Request) -> dict:
        get_authenticated_session(request, admin_only=True)
        return serialize_global_background(
            resolved_settings,
            request,
            storage_service.read_global_background_config(),
        )

    @app.post("/admin/background")
    async def upload_admin_background(
        request: Request,
        background: UploadFile = File(...),
    ) -> dict:
        get_authenticated_session(request, admin_only=True)
        enforce_usage_guardrails(
            check_storage=True,
            extra_storage_bytes=request_content_length(request),
            d1_rows_written_daily=1,
            r2_class_a_rolling_30d=1,
        )
        staged = await storage_service.stage_global_background(background)
        try:
            enforce_usage_guardrails(check_storage=True, d1_rows_written_daily=1, r2_class_a_rolling_30d=1)
            config = storage_service.publish_global_background(staged)
        except Exception:
            storage_service.delete_global_background(staged)
            raise
        record_usage(d1_rows_written_daily=1, r2_class_a_rolling_30d=1)
        return serialize_global_background(resolved_settings, request, config)

    @app.get("/admin/usage")
    async def get_admin_usage(request: Request) -> dict:
        get_authenticated_session(request, admin_only=True)
        return serialize_usage_summary(resolved_settings, read_usage_config(), read_usage_counters())

    @app.post("/admin/usage")
    async def update_admin_usage(request: Request, payload: UsageLimitUpdateRequest) -> dict:
        get_authenticated_session(request, admin_only=True)
        return serialize_usage_summary(resolved_settings, write_usage_config(payload), read_usage_counters())

    return app
