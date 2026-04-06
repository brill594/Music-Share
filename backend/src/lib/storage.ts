import type { ObjectStorage, ShareRepository } from "./contracts";
import type { AppSettings } from "./config";
import { ApiError } from "./errors";
import { nowIso, type ShareRecord } from "./models";
import type { R2Bucket } from "./types";
import {
  buildContentDisposition,
  buildDownloadFilename,
  extensionForMime,
  normalizeMimeType,
  randomShareCode,
} from "./validation";

export class R2ObjectStorage implements ObjectStorage {
  constructor(private readonly bucket: R2Bucket) {}

  async putObject(key: string, body: BodyInit, contentType: string): Promise<void> {
    await this.bucket.put(key, body, {
      httpMetadata: {
        contentType,
      },
    });
  }

  async getObject(key: string) {
    const object = await this.bucket.get(key);
    if (object === null) {
      return null;
    }
    return {
      body: object.body,
      contentType: object.httpMetadata?.contentType ?? null,
      size: typeof object.size === "number" ? object.size : null,
      etag: object.httpEtag ?? null,
    };
  }

  async deleteObject(key: string): Promise<void> {
    await this.bucket.delete(key);
  }
}

export async function createShareRecord(options: {
  repository: ShareRepository;
  clientInstallId: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  audioMime: string;
  clientCreatedAt: string | null;
  expiresAt: string;
}): Promise<ShareRecord> {
  let shareCode = randomShareCode(16);
  while (await options.repository.shareCodeExists(shareCode)) {
    shareCode = randomShareCode(16);
  }

  const shareUuid = crypto.randomUUID();
  const audioExtension = extensionForMime(options.audioMime);
  return {
    uuid: shareUuid,
    share_code: shareCode,
    client_install_id: options.clientInstallId,
    title: options.title,
    artist: options.artist,
    album: options.album,
    duration_ms: options.durationMs,
    audio_mime: options.audioMime,
    audio_path: `shares/${shareUuid}/audio${audioExtension}`,
    cover_mime: null,
    cover_path: null,
    background_mime: null,
    background_path: null,
    created_at: nowIso(),
    client_created_at: options.clientCreatedAt,
    expires_at: options.expiresAt,
    terminated_at: null,
    status: "active",
  };
}

export async function persistUploads(options: {
  storage: ObjectStorage;
  settings: AppSettings;
  share: ShareRecord;
  audioFile: File;
  coverFile: File | null;
}): Promise<ShareRecord> {
  if (options.audioFile.size > options.settings.maxAudioUploadBytes) {
    throw new ApiError(413, `Upload exceeds ${options.settings.maxAudioUploadBytes} bytes.`);
  }

  const writtenKeys = [options.share.audio_path];
  try {
    await options.storage.putObject(
      options.share.audio_path,
      await options.audioFile.arrayBuffer(),
      options.share.audio_mime,
    );

    if (options.coverFile === null) {
      return options.share;
    }

    const coverMime = normalizeMimeType(options.coverFile.type, {
      allowed: options.settings.allowedImageMimeTypes,
      fieldName: "cover content_type",
    });
    if (options.coverFile.size > options.settings.maxCoverUploadBytes) {
      throw new ApiError(413, `Upload exceeds ${options.settings.maxCoverUploadBytes} bytes.`);
    }

    const coverPath = `shares/${options.share.uuid}/cover${extensionForMime(coverMime)}`;
    writtenKeys.push(coverPath);
    await options.storage.putObject(coverPath, await options.coverFile.arrayBuffer(), coverMime);
    return {
      ...options.share,
      cover_mime: coverMime,
      cover_path: coverPath,
    };
  } catch (error) {
    await Promise.all(
      writtenKeys.map(async (key) => {
        try {
          await options.storage.deleteObject(key);
        } catch {
          return;
        }
      }),
    );
    throw error;
  }
}

export async function deleteShareAssets(storage: ObjectStorage, share: ShareRecord): Promise<void> {
  await storage.deleteObject(share.audio_path);
  if (share.cover_path) {
    await storage.deleteObject(share.cover_path);
  }
  if (share.background_path) {
    await storage.deleteObject(share.background_path);
  }
}

export async function audioResponse(storage: ObjectStorage, share: ShareRecord): Promise<Response> {
  const object = await storage.getObject(share.audio_path);
  if (object === null) {
    throw new ApiError(404, "Audio not found.");
  }
  const headers = new Headers();
  headers.set("content-type", object.contentType ?? share.audio_mime);
  headers.set("content-disposition", buildContentDisposition(buildDownloadFilename(share)));
  headers.set("cache-control", "private, max-age=60");
  if (object.size !== null) {
    headers.set("content-length", String(object.size));
  }
  if (object.etag) {
    headers.set("etag", object.etag);
  }
  return new Response(object.body, {
    status: 200,
    headers,
  });
}

export async function coverResponse(storage: ObjectStorage, share: ShareRecord): Promise<Response> {
  if (share.cover_path === null || share.cover_mime === null) {
    throw new ApiError(404, "Cover not found.");
  }
  const object = await storage.getObject(share.cover_path);
  if (object === null) {
    throw new ApiError(404, "Cover not found.");
  }
  const headers = new Headers();
  headers.set("content-type", object.contentType ?? share.cover_mime);
  headers.set("cache-control", "private, max-age=60");
  if (object.size !== null) {
    headers.set("content-length", String(object.size));
  }
  if (object.etag) {
    headers.set("etag", object.etag);
  }
  return new Response(object.body, {
    status: 200,
    headers,
  });
}

export async function persistBackgroundUpload(options: {
  storage: ObjectStorage;
  settings: AppSettings;
  share: ShareRecord;
  backgroundFile: File;
}): Promise<Pick<ShareRecord, "background_mime" | "background_path">> {
  const backgroundMime = normalizeMimeType(options.backgroundFile.type, {
    allowed: options.settings.allowedImageMimeTypes,
    fieldName: "background content_type",
  });
  if (options.backgroundFile.size > options.settings.maxCoverUploadBytes) {
    throw new ApiError(413, `Upload exceeds ${options.settings.maxCoverUploadBytes} bytes.`);
  }

  const backgroundPath = `shares/${options.share.uuid}/background${extensionForMime(backgroundMime)}`;
  await options.storage.putObject(backgroundPath, await options.backgroundFile.arrayBuffer(), backgroundMime);
  return {
    background_mime: backgroundMime,
    background_path: backgroundPath,
  };
}

export async function backgroundResponse(storage: ObjectStorage, share: ShareRecord): Promise<Response> {
  if (share.background_path === null || share.background_mime === null) {
    throw new ApiError(404, "Background not found.");
  }
  const object = await storage.getObject(share.background_path);
  if (object === null) {
    throw new ApiError(404, "Background not found.");
  }
  const headers = new Headers();
  headers.set("content-type", object.contentType ?? share.background_mime);
  headers.set("cache-control", "private, max-age=60");
  if (object.size !== null) {
    headers.set("content-length", String(object.size));
  }
  if (object.etag) {
    headers.set("etag", object.etag);
  }
  return new Response(object.body, {
    status: 200,
    headers,
  });
}
