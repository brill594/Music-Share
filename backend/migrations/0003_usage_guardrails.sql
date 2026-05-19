ALTER TABLE shares ADD COLUMN audio_bytes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE shares ADD COLUMN cover_bytes INTEGER;
ALTER TABLE shares ADD COLUMN background_bytes INTEGER;

CREATE TABLE IF NOT EXISTS usage_limits (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    enabled INTEGER NOT NULL DEFAULT 1,
    d1_rows_read_daily_limit INTEGER NOT NULL,
    d1_rows_written_daily_limit INTEGER NOT NULL,
    d1_storage_bytes_limit INTEGER NOT NULL,
    r2_class_a_rolling_30d_limit INTEGER NOT NULL,
    r2_class_b_rolling_30d_limit INTEGER NOT NULL,
    r2_storage_gb_month_limit REAL NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS usage_state (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    last_usage_day TEXT NOT NULL,
    d1_storage_bytes INTEGER NOT NULL DEFAULT 0,
    r2_storage_live_bytes INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS usage_daily_metrics (
    usage_day TEXT PRIMARY KEY,
    d1_rows_read INTEGER NOT NULL DEFAULT 0,
    d1_rows_written INTEGER NOT NULL DEFAULT 0,
    r2_class_a INTEGER NOT NULL DEFAULT 0,
    r2_class_b INTEGER NOT NULL DEFAULT 0,
    r2_storage_peak_bytes INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_daily_metrics_updated_at
ON usage_daily_metrics (updated_at);
