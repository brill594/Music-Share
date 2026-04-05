from __future__ import annotations

import sqlite3
from contextlib import closing
from pathlib import Path

from .models import ShareRecord, SessionRecord, parse_datetime


class Database:
    def __init__(self, path: Path) -> None:
        self.path = path

    def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS shares (
                    uuid TEXT PRIMARY KEY,
                    share_code TEXT NOT NULL UNIQUE,
                    client_install_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    audio_mime TEXT NOT NULL,
                    audio_path TEXT NOT NULL,
                    cover_mime TEXT,
                    cover_path TEXT,
                    created_at TEXT NOT NULL,
                    client_created_at TEXT,
                    expires_at TEXT NOT NULL,
                    terminated_at TEXT,
                    status TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_shares_client_install
                ON shares (client_install_id, created_at DESC)
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_shares_expires_at
                ON shares (expires_at)
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    session_key_hash TEXT PRIMARY KEY,
                    role TEXT NOT NULL,
                    auth_type TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_sessions_expires_at
                ON sessions (expires_at)
                """
            )
            connection.commit()

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        return connection

    def share_code_exists(self, share_code: str) -> bool:
        with self.connect() as connection, closing(
            connection.execute(
                "SELECT 1 FROM shares WHERE share_code = ? LIMIT 1", (share_code,)
            )
        ) as cursor:
            return cursor.fetchone() is not None

    def insert_share(self, share: ShareRecord) -> None:
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO shares (
                    uuid, share_code, client_install_id, title, artist, album,
                    duration_ms, audio_mime, audio_path, cover_mime, cover_path,
                    created_at, client_created_at, expires_at, terminated_at, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    share.uuid,
                    share.share_code,
                    share.client_install_id,
                    share.title,
                    share.artist,
                    share.album,
                    share.duration_ms,
                    share.audio_mime,
                    share.audio_path,
                    share.cover_mime,
                    share.cover_path,
                    share.created_at.isoformat(),
                    share.client_created_at.isoformat()
                    if share.client_created_at is not None
                    else None,
                    share.expires_at.isoformat(),
                    share.terminated_at.isoformat()
                    if share.terminated_at is not None
                    else None,
                    share.status,
                ),
            )
            connection.commit()

    def get_share_by_code(self, share_code: str) -> ShareRecord | None:
        with self.connect() as connection, closing(
            connection.execute(
                "SELECT * FROM shares WHERE share_code = ? LIMIT 1", (share_code,)
            )
        ) as cursor:
            row = cursor.fetchone()
        if row is None:
            return None
        return self._to_share(row)

    def list_shares_by_client(self, client_install_id: str) -> list[ShareRecord]:
        with self.connect() as connection, closing(
            connection.execute(
                """
                SELECT * FROM shares
                WHERE client_install_id = ?
                ORDER BY created_at DESC
                """,
                (client_install_id,),
            )
        ) as cursor:
            rows = cursor.fetchall()
        return [self._to_share(row) for row in rows]

    def list_all_shares(self) -> list[ShareRecord]:
        with self.connect() as connection, closing(
            connection.execute(
                "SELECT * FROM shares ORDER BY created_at DESC"
            )
        ) as cursor:
            rows = cursor.fetchall()
        return [self._to_share(row) for row in rows]

    def terminate_share(self, share_code: str, terminated_at: str) -> None:
        with self.connect() as connection:
            connection.execute(
                """
                UPDATE shares
                SET terminated_at = COALESCE(terminated_at, ?), status = 'terminated'
                WHERE share_code = ?
                """,
                (terminated_at, share_code),
            )
            connection.commit()

    def list_cleanup_candidates(self, now_iso: str) -> list[ShareRecord]:
        with self.connect() as connection, closing(
            connection.execute(
                """
                SELECT * FROM shares
                WHERE terminated_at IS NOT NULL OR expires_at <= ?
                ORDER BY created_at ASC
                """,
                (now_iso,),
            )
        ) as cursor:
            rows = cursor.fetchall()
        return [self._to_share(row) for row in rows]

    def delete_share(self, share_uuid: str) -> None:
        with self.connect() as connection:
            connection.execute("DELETE FROM shares WHERE uuid = ?", (share_uuid,))
            connection.commit()

    def upsert_session(self, session: SessionRecord) -> None:
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO sessions (
                    session_key_hash, role, auth_type, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(session_key_hash) DO UPDATE SET
                    role = excluded.role,
                    auth_type = excluded.auth_type,
                    created_at = excluded.created_at,
                    expires_at = excluded.expires_at
                """,
                (
                    session.session_key_hash,
                    session.role,
                    session.auth_type,
                    session.created_at.isoformat(),
                    session.expires_at.isoformat(),
                ),
            )
            connection.commit()

    def get_session(self, session_key_hash: str) -> SessionRecord | None:
        with self.connect() as connection, closing(
            connection.execute(
                "SELECT * FROM sessions WHERE session_key_hash = ? LIMIT 1",
                (session_key_hash,),
            )
        ) as cursor:
            row = cursor.fetchone()
        if row is None:
            return None
        return SessionRecord(
            session_key_hash=row["session_key_hash"],
            role=row["role"],
            auth_type=row["auth_type"],
            created_at=parse_datetime(row["created_at"]),
            expires_at=parse_datetime(row["expires_at"]),
        )

    def delete_expired_sessions(self, now_iso: str) -> None:
        with self.connect() as connection:
            connection.execute("DELETE FROM sessions WHERE expires_at <= ?", (now_iso,))
            connection.commit()

    @staticmethod
    def _to_share(row: sqlite3.Row) -> ShareRecord:
        return ShareRecord(
            uuid=row["uuid"],
            share_code=row["share_code"],
            client_install_id=row["client_install_id"],
            title=row["title"],
            artist=row["artist"],
            album=row["album"],
            duration_ms=int(row["duration_ms"]),
            audio_mime=row["audio_mime"],
            audio_path=row["audio_path"],
            cover_mime=row["cover_mime"],
            cover_path=row["cover_path"],
            created_at=parse_datetime(row["created_at"]),
            client_created_at=parse_datetime(row["client_created_at"])
            if row["client_created_at"]
            else None,
            expires_at=parse_datetime(row["expires_at"]),
            terminated_at=parse_datetime(row["terminated_at"])
            if row["terminated_at"]
            else None,
            status=row["status"],
        )
