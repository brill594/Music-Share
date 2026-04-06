export type ShareStatus = "active" | "expired" | "terminated";
export type SessionRole = "user" | "admin";
export type AuthType = "api_key";

export interface SessionRecord {
  session_key_hash: string;
  role: SessionRole;
  auth_type: AuthType;
  created_at: string;
  expires_at: string;
}

export interface ShareRecord {
  uuid: string;
  share_code: string;
  client_install_id: string;
  title: string;
  artist: string;
  album: string;
  duration_ms: number;
  audio_mime: string;
  audio_path: string;
  cover_mime: string | null;
  cover_path: string | null;
  background_mime: string | null;
  background_path: string | null;
  created_at: string;
  client_created_at: string | null;
  expires_at: string;
  terminated_at: string | null;
  status: string;
}

function hasExplicitTimezone(value: string): boolean {
  return /[zZ]$|[+-]\d{2}:\d{2}$/.test(value);
}

export function nowIso(): string {
  return new Date().toISOString();
}

export function parseDateTime(value: string): string {
  const normalized = value.trim();
  const candidate = hasExplicitTimezone(normalized) ? normalized : `${normalized}Z`;
  const parsed = new Date(candidate);
  if (Number.isNaN(parsed.valueOf())) {
    throw new Error("Invalid ISO 8601 timestamp.");
  }
  return parsed.toISOString();
}

export function effectiveStatus(share: ShareRecord, now: string = nowIso()): ShareStatus {
  if (share.terminated_at !== null) {
    return "terminated";
  }
  if (Date.parse(share.expires_at) <= Date.parse(now)) {
    return "expired";
  }
  return "active";
}

export function remainingSeconds(share: ShareRecord, now: string = nowIso()): number {
  if (effectiveStatus(share, now) !== "active") {
    return 0;
  }
  const seconds = Math.floor((Date.parse(share.expires_at) - Date.parse(now)) / 1000);
  return Math.max(seconds, 0);
}
