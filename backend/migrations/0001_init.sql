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
);

CREATE INDEX IF NOT EXISTS idx_shares_client_install
ON shares (client_install_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_shares_expires_at
ON shares (expires_at);

CREATE TABLE IF NOT EXISTS sessions (
    session_key_hash TEXT PRIMARY KEY,
    role TEXT NOT NULL,
    auth_type TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_expires_at
ON sessions (expires_at);
