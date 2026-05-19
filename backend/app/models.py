from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Literal


ShareStatus = Literal["active", "expired", "terminated"]
SessionRole = Literal["user", "admin"]
AuthType = Literal["api_key"]


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def ensure_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def parse_datetime(value: str) -> datetime:
    normalized = value.strip()
    if normalized.endswith("Z"):
        normalized = normalized[:-1] + "+00:00"
    return ensure_utc(datetime.fromisoformat(normalized))


def to_iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    return ensure_utc(value).isoformat().replace("+00:00", "Z")


@dataclass(slots=True)
class SessionRecord:
    session_key_hash: str
    role: SessionRole
    auth_type: AuthType
    created_at: datetime
    expires_at: datetime


@dataclass(slots=True)
class ShareRecord:
    uuid: str
    share_code: str
    client_install_id: str
    title: str
    artist: str
    album: str
    duration_ms: int
    audio_mime: str
    audio_path: str
    cover_mime: str | None
    cover_path: str | None
    created_at: datetime
    client_created_at: datetime | None
    expires_at: datetime
    terminated_at: datetime | None
    status: str

    def effective_status(self, now: datetime | None = None) -> ShareStatus:
        current = ensure_utc(now or utcnow())
        if self.terminated_at is not None:
            return "terminated"
        if self.expires_at <= current:
            return "expired"
        return "active"

    def remaining_seconds(self, now: datetime | None = None) -> int:
        current = ensure_utc(now or utcnow())
        if self.effective_status(current) != "active":
            return 0
        remaining = int((self.expires_at - current).total_seconds())
        return max(remaining, 0)

    def metadata_payload(self) -> dict[str, Any]:
        return {
            "uuid": self.uuid,
            "share_code": self.share_code,
            "client_install_id": self.client_install_id,
            "title": self.title,
            "artist": self.artist,
            "album": self.album,
            "duration_ms": self.duration_ms,
            "audio_mime": self.audio_mime,
            "audio_path": self.audio_path,
            "cover_mime": self.cover_mime,
            "cover_path": self.cover_path,
            "created_at": to_iso(self.created_at),
            "client_created_at": to_iso(self.client_created_at),
            "expires_at": to_iso(self.expires_at),
            "terminated_at": to_iso(self.terminated_at),
            "status": self.effective_status(),
        }
