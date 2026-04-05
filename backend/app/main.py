from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile, status
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
    validate_duration,
    validate_text,
)

LOGGER = logging.getLogger("music_share.backend")


class LoginRequest(BaseModel):
    password: str = Field(min_length=1, max_length=256)


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
            database.insert_share(persisted)
        except Exception:
            storage_service.delete_share_assets(persisted)
            raise
        payload = serialize_share_upload(resolved_settings, request, persisted)
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
        share = require_share_available(database.get_share_by_code(share_code))
        return serialize_share_public(resolved_settings, request, share)

    @app.get("/stream/{share_code}")
    async def stream(share_code: str, request: Request):
        enforce_limit(
            request,
            bucket="stream",
            limit=resolved_settings.public_rate_limit,
            window_seconds=resolved_settings.public_rate_window_seconds,
        )
        share = require_share_available(database.get_share_by_code(share_code))
        return storage_service.audio_response(share)

    @app.get("/cover/{share_code}")
    async def cover_route(share_code: str, request: Request):
        enforce_limit(
            request,
            bucket="cover",
            limit=resolved_settings.public_rate_limit,
            window_seconds=resolved_settings.public_rate_window_seconds,
        )
        share = get_share_or_404(share_code)
        if share.effective_status() != "active":
            raise HTTPException(
                status_code=status.HTTP_410_GONE,
                detail=f"Share is {share.effective_status()}.",
            )
        return storage_service.cover_response(share)

    @app.get("/client/shares")
    async def list_client_shares(request: Request) -> dict:
        get_authenticated_session(request)
        client_install_id = require_client_install_id(request)
        items = [
            serialize_share_management(resolved_settings, request, share)
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
        return serialize_share_management(resolved_settings, request, share)

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
        return serialize_share_management(resolved_settings, request, updated)

    @app.get("/admin/tracks")
    async def list_admin_tracks(request: Request) -> dict:
        get_authenticated_session(request, admin_only=True)
        items = [
            serialize_share_management(
                resolved_settings,
                request,
                share,
                include_client_install_id=True,
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
        )

    return app
