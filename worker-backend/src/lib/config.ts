import type { D1Database, R2Bucket } from "./types";

export interface Env {
  MUSIC_SHARE_DB: D1Database;
  MUSIC_SHARE_BUCKET: R2Bucket;
  MUSIC_SHARE_USER_PASSWORD?: string;
  MUSIC_SHARE_ADMIN_PASSWORD?: string;
  MUSIC_SHARE_SESSION_TTL_SECONDS?: string;
  MUSIC_SHARE_SHARE_DEFAULT_TTL_SECONDS?: string;
  MUSIC_SHARE_SHARE_MAX_TTL_SECONDS?: string;
  MUSIC_SHARE_MAX_AUDIO_UPLOAD_BYTES?: string;
  MUSIC_SHARE_MAX_COVER_UPLOAD_BYTES?: string;
  MUSIC_SHARE_MAX_DURATION_MS?: string;
  MUSIC_SHARE_PUBLIC_API_BASE_URL?: string;
  MUSIC_SHARE_PUBLIC_SHARE_BASE_URL?: string;
  MUSIC_SHARE_SESSION_COOKIE_NAME?: string;
}

export interface AppSettings {
  userPassword: string;
  adminPassword: string;
  sessionTtlSeconds: number;
  shareDefaultTtlSeconds: number;
  shareMaxTtlSeconds: number;
  maxAudioUploadBytes: number;
  maxCoverUploadBytes: number;
  maxDurationMs: number;
  publicApiBaseUrl: string | null;
  publicShareBaseUrl: string | null;
  sessionCookieName: string;
  allowedAudioMimeTypes: ReadonlySet<string>;
  allowedImageMimeTypes: ReadonlySet<string>;
}

function getRequiredString(env: Env, key: keyof Env): string {
  const value = env[key];
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`${String(key)} is required.`);
  }
  return value.trim();
}

function getOptionalString(env: Env, key: keyof Env): string | null {
  const value = env[key];
  if (typeof value !== "string") {
    return null;
  }
  const normalized = value.trim();
  return normalized || null;
}

function getInt(env: Env, key: keyof Env, defaultValue: number): number {
  const value = env[key];
  if (typeof value !== "string" || !value.trim()) {
    return defaultValue;
  }
  const parsed = Number.parseInt(value.trim(), 10);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${String(key)} must be an integer.`);
  }
  return parsed;
}

export function loadSettings(env: Env): AppSettings {
  return {
    userPassword: getRequiredString(env, "MUSIC_SHARE_USER_PASSWORD"),
    adminPassword: getRequiredString(env, "MUSIC_SHARE_ADMIN_PASSWORD"),
    sessionTtlSeconds: getInt(env, "MUSIC_SHARE_SESSION_TTL_SECONDS", 86_400),
    shareDefaultTtlSeconds: getInt(env, "MUSIC_SHARE_SHARE_DEFAULT_TTL_SECONDS", 86_400),
    shareMaxTtlSeconds: getInt(env, "MUSIC_SHARE_SHARE_MAX_TTL_SECONDS", 2_592_000),
    maxAudioUploadBytes: getInt(env, "MUSIC_SHARE_MAX_AUDIO_UPLOAD_BYTES", 64 * 1024 * 1024),
    maxCoverUploadBytes: getInt(env, "MUSIC_SHARE_MAX_COVER_UPLOAD_BYTES", 8 * 1024 * 1024),
    maxDurationMs: getInt(env, "MUSIC_SHARE_MAX_DURATION_MS", 43_200_000),
    publicApiBaseUrl: getOptionalString(env, "MUSIC_SHARE_PUBLIC_API_BASE_URL"),
    publicShareBaseUrl: getOptionalString(env, "MUSIC_SHARE_PUBLIC_SHARE_BASE_URL"),
    sessionCookieName: getOptionalString(env, "MUSIC_SHARE_SESSION_COOKIE_NAME") ?? "music_share_session",
    allowedAudioMimeTypes: new Set([
      "audio/aac",
      "audio/mp4",
      "audio/mpeg",
      "audio/ogg",
      "audio/x-m4a",
    ]),
    allowedImageMimeTypes: new Set([
      "image/jpeg",
      "image/png",
      "image/webp",
    ]),
  };
}
