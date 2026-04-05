import type { SessionRecord, ShareRecord } from "./models";

export interface ShareRepository {
  shareCodeExists(shareCode: string): Promise<boolean>;
  insertShare(share: ShareRecord): Promise<void>;
  getShareByCode(shareCode: string): Promise<ShareRecord | null>;
  listSharesByClient(clientInstallId: string): Promise<ShareRecord[]>;
  listAllShares(): Promise<ShareRecord[]>;
  terminateShare(shareCode: string, terminatedAt: string): Promise<void>;
  listCleanupCandidates(nowIso: string): Promise<ShareRecord[]>;
  deleteShare(shareUuid: string): Promise<void>;
  upsertSession(session: SessionRecord): Promise<void>;
  getSession(sessionKeyHash: string): Promise<SessionRecord | null>;
  deleteExpiredSessions(nowIso: string): Promise<void>;
}

export interface StoredObject {
  body: BodyInit | null;
  contentType: string | null;
  size: number | null;
  etag: string | null;
}

export interface ObjectStorage {
  putObject(key: string, body: BodyInit, contentType: string): Promise<void>;
  getObject(key: string): Promise<StoredObject | null>;
  deleteObject(key: string): Promise<void>;
}
