import type { DownloadProgress, PublicTrack } from "@/types/share";

export class HttpError extends Error {
  status: number;
  detail: string;

  constructor(status: number, detail: string) {
    super(detail || `Request failed with status ${status}.`);
    this.name = "HttpError";
    this.status = status;
    this.detail = detail;
  }
}

function resolveApiBaseUrl(): string {
  const configuredValue = import.meta.env.VITE_API_BASE_URL;
  const configured = typeof configuredValue === "string" ? configuredValue.trim() : "";
  if (configured) {
    return configured.endsWith("/") ? configured : `${configured}/`;
  }

  return `${window.location.origin}/`;
}

function buildApiUrl(path: string): string {
  const normalizedPath = path.replace(/^\/+/, "");
  return new URL(normalizedPath, resolveApiBaseUrl()).toString();
}

async function parseErrorDetail(response: Response): Promise<string> {
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    const payload = (await response.json()) as { detail?: string };
    if (payload.detail) {
      return payload.detail;
    }
  }

  const text = await response.text();
  return text || `Request failed with status ${response.status}.`;
}

async function ensureOk(response: Response): Promise<Response> {
  if (!response.ok) {
    throw new HttpError(response.status, await parseErrorDetail(response));
  }
  return response;
}

export async function fetchTrack(shareCode: string, signal?: AbortSignal): Promise<PublicTrack> {
  const response = await ensureOk(
    await fetch(buildApiUrl(`track/${encodeURIComponent(shareCode)}`), {
      signal,
      credentials: "include",
      headers: {
        Accept: "application/json",
      },
    }),
  );
  return (await response.json()) as PublicTrack;
}

export async function downloadAudio(
  streamUrl: string,
  fallbackMimeType: string,
  signal: AbortSignal,
  onProgress: (progress: DownloadProgress) => void,
): Promise<Blob> {
  const response = await ensureOk(
    await fetch(streamUrl, {
      signal,
      credentials: "include",
      headers: {
        Accept: fallbackMimeType,
      },
    }),
  );

  const contentType = response.headers.get("content-type") || fallbackMimeType;
  const contentLengthHeader = response.headers.get("content-length");
  const total = contentLengthHeader ? Number(contentLengthHeader) : null;
  const reader = response.body?.getReader();

  if (!reader) {
    const blob = await response.blob();
    onProgress({
      loaded: blob.size,
      total: blob.size,
      percent: 100,
      done: true,
    });
    return blob.type ? blob : new Blob([await blob.arrayBuffer()], { type: contentType });
  }

  onProgress({
    loaded: 0,
    total,
    percent: 0,
    done: false,
  });

  const chunks: ArrayBuffer[] = [];
  let loaded = 0;

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    if (!value) {
      continue;
    }

    chunks.push(Uint8Array.from(value).buffer);
    loaded += value.byteLength;

    onProgress({
      loaded,
      total,
      percent: total ? (loaded / total) * 100 : null,
      done: false,
    });
  }

  onProgress({
    loaded,
    total: total ?? loaded,
    percent: 100,
    done: true,
  });

  return new Blob(chunks, { type: contentType });
}

export function toUserMessage(error: unknown): string {
  if (error instanceof HttpError) {
    if (error.status === 404) {
      return "分享链接不存在。";
    }
    if (error.status === 410) {
      return "分享链接已过期或已被终止。";
    }
    return error.detail || "服务暂时不可用。";
  }

  if (error instanceof DOMException && error.name === "AbortError") {
    return "请求已取消。";
  }

  if (error instanceof Error) {
    return error.message || "网络请求失败。";
  }

  return "发生未知错误。";
}
