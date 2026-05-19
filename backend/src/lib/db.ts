import type { ShareRepository } from "./contracts";
import type { SessionRecord, ShareRecord } from "./models";
import type { D1Database } from "./types";
import { normalizeD1QueryMeta, type UsageRecorder } from "./usage";

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
    audio_bytes: Number(row.audio_bytes ?? 0),
    cover_mime: row.cover_mime == null ? null : String(row.cover_mime),
    cover_path: row.cover_path == null ? null : String(row.cover_path),
    cover_bytes: row.cover_bytes == null ? null : Number(row.cover_bytes),
    background_mime: row.background_mime == null ? null : String(row.background_mime),
    background_path: row.background_path == null ? null : String(row.background_path),
    background_bytes: row.background_bytes == null ? null : Number(row.background_bytes),
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
  constructor(
    private readonly database: D1Database,
    private readonly usageRecorder?: UsageRecorder,
  ) {}

  private async first<T = Record<string, unknown>>(statement: ReturnType<D1Database["prepare"]>): Promise<T | null> {
    const result = await statement.all<T>();
    this.usageRecorder?.recordD1Meta(normalizeD1QueryMeta(result.meta));
    return (result.results ?? [])[0] ?? null;
  }

  private async all<T = Record<string, unknown>>(statement: ReturnType<D1Database["prepare"]>): Promise<T[]> {
    const result = await statement.all<T>();
    this.usageRecorder?.recordD1Meta(normalizeD1QueryMeta(result.meta));
    return result.results ?? [];
  }

  private async run(statement: ReturnType<D1Database["prepare"]>): Promise<void> {
    const result = await statement.run();
    this.usageRecorder?.recordD1Meta(
      normalizeD1QueryMeta(result && typeof result === "object" ? (result as { meta?: unknown }).meta : null),
    );
  }

  async shareCodeExists(shareCode: string): Promise<boolean> {
    const row = await this.first<{ found: number }>(
      this.database.prepare("SELECT 1 AS found FROM shares WHERE share_code = ? LIMIT 1").bind(shareCode),
    );
    return row !== null;
  }

  async insertShare(share: ShareRecord): Promise<void> {
    await this.run(
      this.database.prepare(
        `
          INSERT INTO shares (
            uuid, share_code, client_install_id, title, artist, album,
            duration_ms, audio_mime, audio_path, audio_bytes, cover_mime, cover_path,
            cover_bytes, background_mime, background_path, background_bytes, created_at,
            client_created_at, expires_at, terminated_at, status
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        share.audio_bytes,
        share.cover_mime,
        share.cover_path,
        share.cover_bytes,
        share.background_mime,
        share.background_path,
        share.background_bytes,
        share.created_at,
        share.client_created_at,
        share.expires_at,
        share.terminated_at,
        share.status,
      ),
    );
  }

  async getShareByCode(shareCode: string): Promise<ShareRecord | null> {
    const row = await this.first<RawShareRecord>(
      this.database.prepare("SELECT * FROM shares WHERE share_code = ? LIMIT 1").bind(shareCode),
    );
    return row === null ? null : toShareRecord(row);
  }

  async listSharesByClient(clientInstallId: string): Promise<ShareRecord[]> {
    const result = await this.all<RawShareRecord>(
      this.database.prepare(
        `
          SELECT * FROM shares
          WHERE client_install_id = ?
          ORDER BY created_at DESC
        `,
      )
      .bind(clientInstallId),
    );
    return result.map(toShareRecord);
  }

  async updateShareBackground(
    shareCode: string,
    backgroundMime: string | null,
    backgroundPath: string | null,
    backgroundBytes: number | null,
  ): Promise<void> {
    await this.run(
      this.database.prepare(
        `
          UPDATE shares
          SET background_mime = ?, background_path = ?, background_bytes = ?
          WHERE share_code = ?
        `,
      )
      .bind(backgroundMime, backgroundPath, backgroundBytes, shareCode),
    );
  }

  async listAllShares(): Promise<ShareRecord[]> {
    const result = await this.all<RawShareRecord>(this.database.prepare("SELECT * FROM shares ORDER BY created_at DESC"));
    return result.map(toShareRecord);
  }

  async terminateShare(shareCode: string, terminatedAt: string): Promise<void> {
    await this.run(
      this.database.prepare(
        `
          UPDATE shares
          SET terminated_at = COALESCE(terminated_at, ?), status = 'terminated'
          WHERE share_code = ?
        `,
      )
      .bind(terminatedAt, shareCode),
    );
  }

  async listCleanupCandidates(nowIso: string): Promise<ShareRecord[]> {
    const result = await this.all<RawShareRecord>(
      this.database.prepare(
        `
          SELECT * FROM shares
          WHERE terminated_at IS NOT NULL OR expires_at <= ?
          ORDER BY created_at ASC
        `,
      )
      .bind(nowIso),
    );
    return result.map(toShareRecord);
  }

  async deleteShare(shareUuid: string): Promise<void> {
    await this.run(this.database.prepare("DELETE FROM shares WHERE uuid = ?").bind(shareUuid));
  }

  async upsertSession(session: SessionRecord): Promise<void> {
    await this.run(
      this.database.prepare(
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
      ),
    );
  }

  async getSession(sessionKeyHash: string): Promise<SessionRecord | null> {
    const row = await this.first<RawSessionRecord>(
      this.database.prepare("SELECT * FROM sessions WHERE session_key_hash = ? LIMIT 1").bind(sessionKeyHash),
    );
    return row === null ? null : toSessionRecord(row);
  }

  async deleteExpiredSessions(nowIso: string): Promise<void> {
    await this.run(this.database.prepare("DELETE FROM sessions WHERE expires_at <= ?").bind(nowIso));
  }
}
