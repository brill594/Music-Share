import type { AppSettings } from "./config";
import { ApiError } from "./errors";
import { effectiveStatus, nowIso, remainingSeconds, type ShareRecord } from "./models";

function resolvePublicBaseUrl(configured: string | null, request: Request): string {
  return (configured ?? new URL(request.url).origin).replace(/\/+$/, "");
}

function buildShareUrls(settings: AppSettings, request: Request, share: ShareRecord) {
  const apiBase = resolvePublicBaseUrl(settings.publicApiBaseUrl, request);
  const shareBase = resolvePublicBaseUrl(settings.publicShareBaseUrl, request);
  return {
    share_url: `${shareBase}/${share.share_code}`,
    track_url: `${apiBase}/track/${share.share_code}`,
    stream_url: `${apiBase}/stream/${share.share_code}`,
    cover_url: share.cover_path ? `${apiBase}/cover/${share.share_code}` : null,
    background_url: share.background_path ? `${apiBase}/background/${share.share_code}` : null,
  };
}

export function serializeSharePublic(settings: AppSettings, request: Request, share: ShareRecord) {
  const urls = buildShareUrls(settings, request, share);
  return {
    share_code: share.share_code,
    title: share.title,
    artist: share.artist,
    album: share.album,
    duration_ms: share.duration_ms,
    audio_mime: share.audio_mime,
    share_url: urls.share_url,
    track_url: urls.track_url,
    stream_url: urls.stream_url,
    cover_url: urls.cover_url,
    background_url: urls.background_url,
    created_at: share.created_at,
    expires_at: share.expires_at,
    status: effectiveStatus(share),
  };
}

export function serializeShareUpload(settings: AppSettings, request: Request, share: ShareRecord) {
  return {
    ...serializeSharePublic(settings, request, share),
    uuid: share.uuid,
  };
}

export function serializeShareManagement(
  settings: AppSettings,
  request: Request,
  share: ShareRecord,
  options: {
    includeClientInstallId?: boolean;
  } = {},
) {
  const payload = {
    ...serializeSharePublic(settings, request, share),
    client_created_at: share.client_created_at,
    terminated_at: share.terminated_at,
    remaining_seconds: remainingSeconds(share, nowIso()),
  } as Record<string, unknown>;
  if (options.includeClientInstallId) {
    payload.client_install_id = share.client_install_id;
  }
  return payload;
}

export function requireShareAvailable(share: ShareRecord | null): ShareRecord {
  if (share === null) {
    throw new ApiError(404, "Share not found.");
  }
  const status = effectiveStatus(share);
  if (status !== "active") {
    throw new ApiError(410, `Share is ${status}.`);
  }
  return share;
}
