import { afterEach, describe, expect, it, vi } from "vitest";

import { handleRequest, runCleanup } from "./lib/app";
import type { ShareRepository, ObjectStorage, StoredObject } from "./lib/contracts";
import { effectiveStatus, type SessionRecord, type ShareRecord } from "./lib/models";
import type { Env } from "./lib/config";
import type { D1Database, R2Bucket } from "./lib/types";

class MemoryRepository implements ShareRepository {
  private readonly shares = new Map<string, ShareRecord>();
  private readonly sessions = new Map<string, SessionRecord>();

  async shareCodeExists(shareCode: string): Promise<boolean> {
    return Array.from(this.shares.values()).some((share) => share.share_code === shareCode);
  }

  async insertShare(share: ShareRecord): Promise<void> {
    this.shares.set(share.uuid, structuredClone(share));
  }

  async getShareByCode(shareCode: string): Promise<ShareRecord | null> {
    const share = Array.from(this.shares.values()).find((item) => item.share_code === shareCode);
    return share ? structuredClone(share) : null;
  }

  async listSharesByClient(clientInstallId: string): Promise<ShareRecord[]> {
    return Array.from(this.shares.values())
      .filter((share) => share.client_install_id === clientInstallId)
      .sort((left, right) => right.created_at.localeCompare(left.created_at))
      .map((share) => structuredClone(share));
  }

  async listAllShares(): Promise<ShareRecord[]> {
    return Array.from(this.shares.values())
      .sort((left, right) => right.created_at.localeCompare(left.created_at))
      .map((share) => structuredClone(share));
  }

  async updateShareBackground(
    shareCode: string,
    backgroundMime: string | null,
    backgroundPath: string | null,
  ): Promise<void> {
    for (const [uuid, share] of this.shares.entries()) {
      if (share.share_code !== shareCode) {
        continue;
      }
      this.shares.set(uuid, {
        ...share,
        background_mime: backgroundMime,
        background_path: backgroundPath,
      });
      return;
    }
  }

  async terminateShare(shareCode: string, terminatedAt: string): Promise<void> {
    for (const [uuid, share] of this.shares.entries()) {
      if (share.share_code !== shareCode) {
        continue;
      }
      this.shares.set(uuid, {
        ...share,
        terminated_at: share.terminated_at ?? terminatedAt,
        status: "terminated",
      });
      return;
    }
  }

  async listCleanupCandidates(nowIso: string): Promise<ShareRecord[]> {
    return Array.from(this.shares.values())
      .filter(
        (share) =>
          share.terminated_at !== null || Date.parse(share.expires_at) <= Date.parse(nowIso),
      )
      .sort((left, right) => left.created_at.localeCompare(right.created_at))
      .map((share) => structuredClone(share));
  }

  async deleteShare(shareUuid: string): Promise<void> {
    this.shares.delete(shareUuid);
  }

  async upsertSession(session: SessionRecord): Promise<void> {
    this.sessions.set(session.session_key_hash, structuredClone(session));
  }

  async getSession(sessionKeyHash: string): Promise<SessionRecord | null> {
    const session = this.sessions.get(sessionKeyHash);
    return session ? structuredClone(session) : null;
  }

  async deleteExpiredSessions(nowIso: string): Promise<void> {
    for (const [key, session] of this.sessions.entries()) {
      if (Date.parse(session.expires_at) <= Date.parse(nowIso)) {
        this.sessions.delete(key);
      }
    }
  }

  sessionCount(): number {
    return this.sessions.size;
  }
}

class MemoryStorage implements ObjectStorage {
  private readonly objects = new Map<
    string,
    {
      body: Uint8Array;
      contentType: string;
    }
  >();

  async putObject(key: string, body: BodyInit, contentType: string): Promise<void> {
    this.objects.set(key, {
      body: await toBytes(body),
      contentType,
    });
  }

  async getObject(key: string): Promise<StoredObject | null> {
    const object = this.objects.get(key);
    if (!object) {
      return null;
    }
    return {
      body: new Uint8Array(object.body),
      contentType: object.contentType,
      size: object.body.byteLength,
      etag: null,
    };
  }

  async deleteObject(key: string): Promise<void> {
    this.objects.delete(key);
  }

  has(key: string): boolean {
    return this.objects.has(key);
  }
}

async function toBytes(body: BodyInit): Promise<Uint8Array> {
  if (typeof body === "string") {
    return new TextEncoder().encode(body);
  }
  if (body instanceof Blob) {
    return new Uint8Array(await body.arrayBuffer());
  }
  if (body instanceof ArrayBuffer) {
    return new Uint8Array(body);
  }
  if (ArrayBuffer.isView(body)) {
    return new Uint8Array(body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength));
  }
  return new Uint8Array(await new Response(body).arrayBuffer());
}

function createEnv(overrides: Partial<Env> = {}): Env {
  return {
    MUSIC_SHARE_DB: {} as D1Database,
    MUSIC_SHARE_BUCKET: {} as R2Bucket,
    MUSIC_SHARE_USER_PASSWORD: "user-password",
    MUSIC_SHARE_ADMIN_PASSWORD: "admin-password",
    MUSIC_SHARE_PUBLIC_API_BASE_URL: "https://api.example.test",
    MUSIC_SHARE_PUBLIC_SHARE_BASE_URL: "https://share.example.test",
    ...overrides,
  };
}

function createHarness(overrides: Partial<Env> = {}) {
  const env = createEnv(overrides);
  const repository = new MemoryRepository();
  const storage = new MemoryStorage();

  async function request(input: {
    path: string;
    method?: string;
    headers?: HeadersInit;
    body?: BodyInit | null;
  }): Promise<Response> {
    return handleRequest(
      new Request(`https://api.example.test${input.path}`, {
        method: input.method ?? "GET",
        headers: input.headers,
        body: input.body ?? null,
      }),
      env,
      {
        repository,
        storage,
      },
    );
  }

  return {
    env,
    repository,
    storage,
    request,
  };
}

async function login(
  harness: ReturnType<typeof createHarness>,
  password: string,
): Promise<{ sessionKey: string; response: Response }> {
  const response = await harness.request({
    path: "/auth/login",
    method: "POST",
    headers: {
      "content-type": "application/json",
    },
    body: JSON.stringify({ password }),
  });
  expect(response.status).toBe(200);
  const payload = (await response.json()) as { session_key: string };
  return {
    sessionKey: payload.session_key,
    response,
  };
}

async function uploadSample(
  harness: ReturnType<typeof createHarness>,
  sessionKey: string,
  clientInstallId = "install-0001",
): Promise<Record<string, unknown>> {
  const form = new FormData();
  form.set("title", "Test Song");
  form.set("artist", "Test Artist");
  form.set("album", "Test Album");
  form.set("duration_ms", "123000");
  form.set("audio_mime", "audio/ogg");
  form.set("client_created_at", "2026-04-05T12:00:00Z");
  form.set("expire_after_seconds", "120");
  form.set("file", new File([new Uint8Array([1, 2, 3, 4])], "track.ogg", { type: "audio/ogg" }));
  form.set("cover", new File([new Uint8Array([5, 6, 7])], "cover.jpg", { type: "image/jpeg" }));

  const response = await harness.request({
    path: "/upload",
    method: "POST",
    headers: {
      "X-Session-Key": sessionKey,
      "X-Client-Install-Id": clientInstallId,
    },
    body: form,
  });
  expect(response.status).toBe(200);
  return (await response.json()) as Record<string, unknown>;
}

describe("Cloudflare Worker backend compatibility", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("supports upload, public queries, stream/cover responses, and client termination", async () => {
    const harness = createHarness();
    const { sessionKey, response: loginResponse } = await login(harness, "user-password");

    expect(loginResponse.headers.get("set-cookie")).toContain("music_share_session=");

    const uploaded = await uploadSample(harness, sessionKey);
    const shareCode = String(uploaded.share_code);
    const shareUuid = String(uploaded.uuid);

    const trackResponse = await harness.request({
      path: `/track/${shareCode}`,
      headers: {
        Origin: "https://music-share-web-player.pages.dev",
      },
    });
    expect(trackResponse.status).toBe(200);
    expect(trackResponse.headers.get("access-control-allow-origin")).toBe(
      "https://music-share-web-player.pages.dev",
    );
    const trackPayload = (await trackResponse.json()) as Record<string, unknown>;
    expect(trackPayload.share_code).toBe(shareCode);
    expect(trackPayload).not.toHaveProperty("uuid");
    expect(trackPayload.stream_url).toBe(`https://api.example.test/stream/${shareCode}`);

    const streamResponse = await harness.request({
      path: `/stream/${shareCode}`,
      headers: {
        Origin: "https://music-share-web-player.pages.dev",
      },
    });
    expect(streamResponse.status).toBe(200);
    expect(streamResponse.headers.get("access-control-allow-origin")).toBe(
      "https://music-share-web-player.pages.dev",
    );
    expect(streamResponse.headers.get("access-control-expose-headers")).toContain("Content-Length");
    expect(streamResponse.headers.get("content-type")).toBe("audio/ogg");
    expect(streamResponse.headers.get("content-disposition")).toContain("Test_Song.ogg");
    expect(new Uint8Array(await streamResponse.arrayBuffer())).toEqual(new Uint8Array([1, 2, 3, 4]));

    const coverResponse = await harness.request({
      path: `/cover/${shareCode}`,
    });
    expect(coverResponse.status).toBe(200);
    expect(coverResponse.headers.get("content-type")).toBe("image/jpeg");
    expect(new Uint8Array(await coverResponse.arrayBuffer())).toEqual(new Uint8Array([5, 6, 7]));

    expect(harness.storage.has(`shares/${shareUuid}/audio.ogg`)).toBe(true);
    expect(harness.storage.has(`shares/${shareUuid}/cover.jpg`)).toBe(true);

    const listResponse = await harness.request({
      path: "/client/shares",
      headers: {
        "X-Session-Key": sessionKey,
        "X-Client-Install-Id": "install-0001",
      },
    });
    expect(listResponse.status).toBe(200);
    const listPayload = (await listResponse.json()) as { count: number };
    expect(listPayload.count).toBe(1);

    const terminateResponse = await harness.request({
      path: `/client/shares/${shareCode}/terminate`,
      method: "POST",
      headers: {
        "X-Session-Key": sessionKey,
        "X-Client-Install-Id": "install-0001",
      },
      body: "",
    });
    expect(terminateResponse.status).toBe(200);
    const terminatePayload = (await terminateResponse.json()) as { status: string };
    expect(terminatePayload.status).toBe("terminated");

    const goneResponse = await harness.request({
      path: `/track/${shareCode}`,
    });
    expect(goneResponse.status).toBe(404);
    expect(harness.storage.has(`shares/${shareUuid}/audio.ogg`)).toBe(false);
    expect(harness.storage.has(`shares/${shareUuid}/cover.jpg`)).toBe(false);
  });

  it("handles CORS preflight requests", async () => {
    const harness = createHarness();
    const response = await harness.request({
      path: "/upload",
      method: "OPTIONS",
      headers: {
        Origin: "https://music-share-web-player.pages.dev",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type,x-client-install-id,x-session-key",
      },
    });

    expect(response.status).toBe(204);
    expect(response.headers.get("access-control-allow-origin")).toBe(
      "https://music-share-web-player.pages.dev",
    );
    expect(response.headers.get("access-control-allow-methods")).toContain("OPTIONS");
    expect(response.headers.get("access-control-allow-headers")).toContain("X-Session-Key");
  });

  it("allows admin listing and terminating any share", async () => {
    const harness = createHarness();
    const { sessionKey: userSession } = await login(harness, "user-password");
    const uploaded = await uploadSample(harness, userSession);

    const { sessionKey: adminSession } = await login(harness, "admin-password");

    const listResponse = await harness.request({
      path: "/admin/tracks",
      headers: {
        "X-Session-Key": adminSession,
      },
    });
    expect(listResponse.status).toBe(200);
    const listPayload = (await listResponse.json()) as {
      count: number;
      items: Array<{ client_install_id?: string }>;
    };
    expect(listPayload.count).toBe(1);
    expect(listPayload.items[0]?.client_install_id).toBe("install-0001");

    const terminateResponse = await harness.request({
      path: `/admin/tracks/${String(uploaded.share_code)}/terminate`,
      method: "POST",
      headers: {
        "X-Session-Key": adminSession,
      },
      body: "",
    });
    expect(terminateResponse.status).toBe(200);
    const terminatePayload = (await terminateResponse.json()) as {
      status: string;
      client_install_id?: string;
    };
    expect(terminatePayload.status).toBe("terminated");
    expect(terminatePayload.client_install_id).toBe("install-0001");

    const listAfterTerminate = await harness.request({
      path: "/admin/tracks",
      headers: {
        "X-Session-Key": adminSession,
      },
    });
    const listAfterPayload = (await listAfterTerminate.json()) as { count: number };
    expect(listAfterPayload.count).toBe(0);
  });

  it("allows admin background upload and public background fetch", async () => {
    const harness = createHarness();
    const { sessionKey: userSession } = await login(harness, "user-password");
    const uploaded = await uploadSample(harness, userSession);
    const shareCode = String(uploaded.share_code);
    const shareUuid = String(uploaded.uuid);
    const { sessionKey: adminSession } = await login(harness, "admin-password");

    const form = new FormData();
    form.set(
      "background",
      new File([new Uint8Array([8, 7, 6, 5])], "background.webp", { type: "image/webp" }),
    );

    const updateResponse = await harness.request({
      path: `/admin/tracks/${shareCode}/background`,
      method: "POST",
      headers: {
        "X-Session-Key": adminSession,
      },
      body: form,
    });
    expect(updateResponse.status).toBe(200);
    const updatePayload = (await updateResponse.json()) as { background_url?: string };
    expect(updatePayload.background_url).toBe(`https://api.example.test/background/${shareCode}`);
    expect(harness.storage.has(`shares/${shareUuid}/background.webp`)).toBe(true);

    const backgroundResponse = await harness.request({
      path: `/background/${shareCode}`,
    });
    expect(backgroundResponse.status).toBe(200);
    expect(backgroundResponse.headers.get("content-type")).toBe("image/webp");
    expect(new Uint8Array(await backgroundResponse.arrayBuffer())).toEqual(new Uint8Array([8, 7, 6, 5]));
  });

  it("cleans up expired shares and expired sessions", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-05T00:00:00Z"));

    const harness = createHarness({
      MUSIC_SHARE_SESSION_TTL_SECONDS: "1",
    });
    const { sessionKey } = await login(harness, "user-password");
    expect(harness.repository.sessionCount()).toBe(1);

    const form = new FormData();
    form.set("title", "Soon Expired");
    form.set("artist", "Test Artist");
    form.set("album", "Test Album");
    form.set("duration_ms", "123000");
    form.set("audio_mime", "audio/ogg");
    form.set("expire_after_seconds", "1");
    form.set("file", new File([new Uint8Array([9, 9, 9])], "track.ogg", { type: "audio/ogg" }));

    const uploadResponse = await harness.request({
      path: "/upload",
      method: "POST",
      headers: {
        "X-Session-Key": sessionKey,
        "X-Client-Install-Id": "install-0002",
      },
      body: form,
    });
    expect(uploadResponse.status).toBe(200);
    const uploaded = (await uploadResponse.json()) as { share_code: string; uuid: string };

    vi.setSystemTime(new Date("2026-04-05T00:00:02Z"));
    await runCleanup(harness.env, {
      repository: harness.repository,
      storage: harness.storage,
    });

    const trackResponse = await harness.request({
      path: `/track/${uploaded.share_code}`,
    });
    expect(trackResponse.status).toBe(404);
    expect(harness.storage.has(`shares/${uploaded.uuid}/audio.ogg`)).toBe(false);
    expect(harness.repository.sessionCount()).toBe(0);
  });

  it("reports effective status consistently", async () => {
    const share: ShareRecord = {
      uuid: "share-1",
      share_code: "abc",
      client_install_id: "install-0001",
      title: "Track",
      artist: "",
      album: "",
      duration_ms: 1000,
      audio_mime: "audio/ogg",
      audio_path: "shares/share-1/audio.ogg",
      cover_mime: null,
      cover_path: null,
      background_mime: null,
      background_path: null,
      created_at: "2026-04-05T00:00:00.000Z",
      client_created_at: null,
      expires_at: "2026-04-05T00:01:00.000Z",
      terminated_at: null,
      status: "active",
    };

    expect(effectiveStatus(share, "2026-04-05T00:00:30.000Z")).toBe("active");
    expect(effectiveStatus(share, "2026-04-05T00:01:00.000Z")).toBe("expired");
    expect(
      effectiveStatus(
        {
          ...share,
          terminated_at: "2026-04-05T00:00:10.000Z",
        },
        "2026-04-05T00:00:30.000Z",
      ),
    ).toBe("terminated");
  });
});
