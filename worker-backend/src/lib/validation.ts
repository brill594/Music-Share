import type { AppSettings } from "./config";
import { ApiError } from "./errors";
import { parseDateTime, type ShareRecord } from "./models";

export const CLIENT_INSTALL_ID_PATTERN = /^[A-Za-z0-9._-]{8,128}$/;
const SAFE_FILENAME_PATTERN = /[^A-Za-z0-9._-]+/g;
const SHARE_CODE_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz";

const MIME_TO_EXTENSION: Record<string, string> = {
  "audio/aac": ".aac",
  "audio/mp4": ".m4a",
  "audio/mpeg": ".mp3",
  "audio/ogg": ".ogg",
  "audio/x-m4a": ".m4a",
  "image/jpeg": ".jpg",
  "image/png": ".png",
  "image/webp": ".webp",
};

function parseInteger(value: string, fieldName: string): number {
  const normalized = value.trim();
  if (!/^-?\d+$/.test(normalized)) {
    throw new ApiError(422, `${fieldName} must be an integer.`);
  }
  const parsed = Number.parseInt(normalized, 10);
  if (!Number.isSafeInteger(parsed)) {
    throw new ApiError(422, `${fieldName} must be an integer.`);
  }
  return parsed;
}

export function requireClientInstallId(request: Request): string {
  const value = (request.headers.get("X-Client-Install-Id") ?? "").trim();
  if (!CLIENT_INSTALL_ID_PATTERN.test(value)) {
    throw new ApiError(422, "Missing or invalid X-Client-Install-Id header.");
  }
  return value;
}

export function normalizeMimeType(
  value: string | null,
  options: {
    allowed: ReadonlySet<string>;
    fieldName: string;
  },
): string {
  const normalized = (value ?? "").trim().toLowerCase();
  if (!options.allowed.has(normalized)) {
    throw new ApiError(422, `${options.fieldName} is not allowed.`);
  }
  return normalized;
}

export function validateText(
  value: string,
  options: {
    fieldName: string;
    maxLength: number;
    required: boolean;
  },
): string {
  const normalized = value.trim();
  if (options.required && !normalized) {
    throw new ApiError(422, `${options.fieldName} is required.`);
  }
  if (normalized.length > options.maxLength) {
    throw new ApiError(422, `${options.fieldName} is too long.`);
  }
  return normalized;
}

export function parseOptionalDateTime(value: string | null, fieldName: string): string | null {
  if (value === null || !value.trim()) {
    return null;
  }
  try {
    return parseDateTime(value);
  } catch {
    throw new ApiError(422, `${fieldName} must be ISO 8601.`);
  }
}

export function parseOptionalInteger(value: string | null, fieldName: string): number | null {
  if (value === null || !value.trim()) {
    return null;
  }
  return parseInteger(value, fieldName);
}

export function parseRequiredInteger(value: string | null, fieldName: string): number {
  if (value === null || !value.trim()) {
    throw new ApiError(422, `${fieldName} is required.`);
  }
  return parseInteger(value, fieldName);
}

export function resolveExpiration(
  settings: AppSettings,
  expireAfterSeconds: number | null,
  expireAt: string | null,
): string {
  if (expireAfterSeconds !== null && expireAt) {
    throw new ApiError(422, "Only one of expire_after_seconds or expire_at may be provided.");
  }
  const now = Date.now();
  if (expireAfterSeconds !== null) {
    if (expireAfterSeconds <= 0 || expireAfterSeconds > settings.shareMaxTtlSeconds) {
      throw new ApiError(422, "expire_after_seconds is outside the allowed range.");
    }
    return new Date(now + expireAfterSeconds * 1000).toISOString();
  }
  if (expireAt) {
    const parsed = parseOptionalDateTime(expireAt, "expire_at");
    if (parsed === null) {
      throw new ApiError(422, "expire_at must be ISO 8601.");
    }
    const parsedTime = Date.parse(parsed);
    if (parsedTime <= now) {
      throw new ApiError(422, "expire_at must be in the future.");
    }
    if (parsedTime > now + settings.shareMaxTtlSeconds * 1000) {
      throw new ApiError(422, "expire_at exceeds the allowed lifetime.");
    }
    return parsed;
  }
  return new Date(now + settings.shareDefaultTtlSeconds * 1000).toISOString();
}

export function validateDuration(durationMs: number, maxDurationMs: number): number {
  if (durationMs <= 0 || durationMs > maxDurationMs) {
    throw new ApiError(422, "duration_ms is outside the allowed range.");
  }
  return durationMs;
}

export function extensionForMime(mimeType: string): string {
  return MIME_TO_EXTENSION[mimeType] ?? "";
}

export function buildDownloadFilename(share: ShareRecord): string {
  const extension = extensionForMime(share.audio_mime);
  const raw = share.title.replace(SAFE_FILENAME_PATTERN, "_").replace(/^[._]+|[._]+$/g, "");
  const base = raw || share.share_code;
  return `${base}${extension}`;
}

export function buildContentDisposition(filename: string): string {
  return `inline; filename*=UTF-8''${encodeURIComponent(filename)}`;
}

export function randomShareCode(length: number): string {
  const values = new Uint32Array(length);
  crypto.getRandomValues(values);
  return Array.from(values, (value) => SHARE_CODE_ALPHABET[value % SHARE_CODE_ALPHABET.length]).join("");
}
