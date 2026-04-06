import type { ShareRepository } from "./contracts";
import type { SessionRecord, ShareRecord } from "./models";
import type { D1Database } from "./types";

type RawShareRecord = Record<string, unknown>;
type RawSessionRecord = Record<string, unknown>;

function toShareRecord(row: RawShareRecord): ShareRecord {
  return {
    uuid: String(row.uuid),
    share_code: String(row.share_code),
    client_install_id: String(row.client_install_id),
    title: String(row.title),
    artist: String(row.artist),
    album: String(row.album),
    duration_ms: Number(row.duration_ms),
    audio_mime: String(row.audio_mime),
    audio_path: String(row.audio_path),
    cover_mime: row.cover_mime == null ? null : String(row.cover_mime),
    cover_path: row.cover_path == null ? null : String(row.cover_path),
    background_mime: row.background_mime == null ? null : String(row.background_mime),
    background_path: row.background_path == null ? null : String(row.background_path),
    created_at: String(row.created_at),
    client_created_at: row.client_created_at == null ? null : String(row.client_created_at),
    expires_at: String(row.expires_at),
    terminated_at: row.terminated_at == null ? null : String(row.terminated_at),
    status: String(row.status),
  };
}

function toSessionRecord(row: RawSessionRecord): SessionRecord {
  return {
    session_key_hash: String(row.session_key_hash),
    role: row.role === "admin" ? "admin" : "user",
    auth_type: "api_key",
    created_at: String(row.created_at),
    expires_at: String(row.expires_at),
  };
}

export class D1ShareRepository implements ShareRepository {
  constructor(private readonly database: D1Database) {}

  async shareCodeExists(shareCode: string): Promise<boolean> {
    const row = await this.database
      .prepare("SELECT 1 AS found FROM shares WHERE share_code = ? LIMIT 1")
      .bind(shareCode)
      .first<{ found: number }>();
    return row !== null;
  }

  async insertShare(share: ShareRecord): Promise<void> {
    await this.database
      .prepare(
        `
          INSERT INTO shares (
            uuid, share_code, client_install_id, title, artist, album,
            duration_ms, audio_mime, audio_path, cover_mime, cover_path,
            background_mime, background_path, created_at, client_created_at,
            expires_at, terminated_at, status
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        `,
      )
      .bind(
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
        share.background_mime,
        share.background_path,
        share.created_at,
        share.client_created_at,
        share.expires_at,
        share.terminated_at,
        share.status,
      )
      .run();
  }

  async getShareByCode(shareCode: string): Promise<ShareRecord | null> {
    const row = await this.database
      .prepare("SELECT * FROM shares WHERE share_code = ? LIMIT 1")
      .bind(shareCode)
      .first<RawShareRecord>();
    return row === null ? null : toShareRecord(row);
  }

  async listSharesByClient(clientInstallId: string): Promise<ShareRecord[]> {
    const result = await this.database
      .prepare(
        `
          SELECT * FROM shares
          WHERE client_install_id = ?
          ORDER BY created_at DESC
        `,
      )
      .bind(clientInstallId)
      .all<RawShareRecord>();
    return (result.results ?? []).map(toShareRecord);
  }

  async updateShareBackground(
    shareCode: string,
    backgroundMime: string | null,
    backgroundPath: string | null,
  ): Promise<void> {
    await this.database
      .prepare(
        `
          UPDATE shares
          SET background_mime = ?, background_path = ?
          WHERE share_code = ?
        `,
      )
      .bind(backgroundMime, backgroundPath, shareCode)
      .run();
  }

  async listAllShares(): Promise<ShareRecord[]> {
    const result = await this.database
      .prepare("SELECT * FROM shares ORDER BY created_at DESC")
      .all<RawShareRecord>();
    return (result.results ?? []).map(toShareRecord);
  }

  async terminateShare(shareCode: string, terminatedAt: string): Promise<void> {
    await this.database
      .prepare(
        `
          UPDATE shares
          SET terminated_at = COALESCE(terminated_at, ?), status = 'terminated'
          WHERE share_code = ?
        `,
      )
      .bind(terminatedAt, shareCode)
      .run();
  }

  async listCleanupCandidates(nowIso: string): Promise<ShareRecord[]> {
    const result = await this.database
      .prepare(
        `
          SELECT * FROM shares
          WHERE terminated_at IS NOT NULL OR expires_at <= ?
          ORDER BY created_at ASC
        `,
      )
      .bind(nowIso)
      .all<RawShareRecord>();
    return (result.results ?? []).map(toShareRecord);
  }

  async deleteShare(shareUuid: string): Promise<void> {
    await this.database.prepare("DELETE FROM shares WHERE uuid = ?").bind(shareUuid).run();
  }

  async upsertSession(session: SessionRecord): Promise<void> {
    await this.database
      .prepare(
        `
          INSERT INTO sessions (
            session_key_hash, role, auth_type, created_at, expires_at
          ) VALUES (?, ?, ?, ?, ?)
          ON CONFLICT(session_key_hash) DO UPDATE SET
            role = excluded.role,
            auth_type = excluded.auth_type,
            created_at = excluded.created_at,
            expires_at = excluded.expires_at
        `,
      )
      .bind(
        session.session_key_hash,
        session.role,
        session.auth_type,
        session.created_at,
        session.expires_at,
      )
      .run();
  }

  async getSession(sessionKeyHash: string): Promise<SessionRecord | null> {
    const row = await this.database
      .prepare("SELECT * FROM sessions WHERE session_key_hash = ? LIMIT 1")
      .bind(sessionKeyHash)
      .first<RawSessionRecord>();
    return row === null ? null : toSessionRecord(row);
  }

  async deleteExpiredSessions(nowIso: string): Promise<void> {
    await this.database.prepare("DELETE FROM sessions WHERE expires_at <= ?").bind(nowIso).run();
  }
}
