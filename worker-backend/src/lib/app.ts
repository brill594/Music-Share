import { AuthService, extractSessionKey } from "./auth";
import type { ObjectStorage, ShareRepository } from "./contracts";
import type { AppSettings, Env } from "./config";
import { loadSettings } from "./config";
import { D1ShareRepository } from "./db";
import { ApiError, isApiError } from "./errors";
import {
  buildSessionCookie,
  corsPreflightResponse,
  errorResponse,
  jsonResponse,
  readFormFile,
  readFormText,
  withCorsHeaders,
} from "./http";
import { effectiveStatus, nowIso, type SessionRecord, type ShareRecord } from "./models";
import {
  audioResponse,
  backgroundResponse,
  coverResponse,
  createShareRecord,
  deleteShareAssets,
  globalBackgroundResponse,
  persistUploads,
  persistBackgroundUpload,
  persistGlobalBackgroundUpload,
  readGlobalBackgroundConfig,
  R2ObjectStorage,
} from "./storage";
import {
  assertUsageAllowsPublicRead,
  assertUsageAllowsUpload,
  createUsageService,
  parseUsageLimitUpdateInput,
  RequestUsageAccumulator,
  serializeUsageSummary,
  type UsageRecorder,
} from "./usage";
import {
  requireShareAvailable,
  serializeGlobalBackground,
  serializeShareManagement,
  serializeSharePublic,
  serializeShareUpload,
} from "./serialization";
import {
  normalizeMimeType,
  parseOptionalDateTime,
  parseOptionalInteger,
  parseRequiredInteger,
  requireClientInstallId,
  resolveExpiration,
  validateDuration,
  validateText,
} from "./validation";

interface AppContext {
  settings: AppSettings;
  repository: ShareRepository;
  storage: ObjectStorage;
  usageService: ReturnType<typeof createUsageService>;
  usageRecorder: RequestUsageAccumulator;
}

export interface AppDependencies {
  settings?: AppSettings;
  repository?: ShareRepository;
  storage?: ObjectStorage;
  usageService?: ReturnType<typeof createUsageService>;
}

function buildBodySize(body: BodyInit): Promise<number> {
  if (typeof body === "string") {
    return Promise.resolve(new TextEncoder().encode(body).byteLength);
  }
  if (body instanceof ArrayBuffer) {
    return Promise.resolve(body.byteLength);
  }
  return new Response(body).arrayBuffer().then((value) => value.byteLength);
}

class EstimatedUsageShareRepository implements ShareRepository {
  constructor(
    private readonly delegate: ShareRepository,
    private readonly usageRecorder: UsageRecorder,
  ) {}

  async shareCodeExists(shareCode: string): Promise<boolean> {
    const result = await this.delegate.shareCodeExists(shareCode);
    this.usageRecorder.recordEstimatedD1Usage({ rowsRead: 1 });
    return result;
  }

  async insertShare(share: ShareRecord): Promise<void> {
    await this.delegate.insertShare(share);
    this.usageRecorder.recordEstimatedD1Usage({ rowsWritten: 1 });
  }

  async getShareByCode(shareCode: string): Promise<ShareRecord | null> {
    const result = await this.delegate.getShareByCode(shareCode);
    this.usageRecorder.recordEstimatedD1Usage({ rowsRead: 1 });
    return result;
  }

  async listSharesByClient(clientInstallId: string): Promise<ShareRecord[]> {
    const result = await this.delegate.listSharesByClient(clientInstallId);
    this.usageRecorder.recordEstimatedD1Usage({ rowsRead: Math.max(1, result.length) });
    return result;
  }

  async listAllShares(): Promise<ShareRecord[]> {
    const result = await this.delegate.listAllShares();
    this.usageRecorder.recordEstimatedD1Usage({ rowsRead: Math.max(1, result.length) });
    return result;
  }

  async updateShareBackground(
    shareCode: string,
    backgroundMime: string | null,
    backgroundPath: string | null,
    backgroundBytes: number | null,
  ): Promise<void> {
    await this.delegate.updateShareBackground(shareCode, backgroundMime, backgroundPath, backgroundBytes);
    this.usageRecorder.recordEstimatedD1Usage({ rowsWritten: 1 });
  }

  async terminateShare(shareCode: string, terminatedAt: string): Promise<void> {
    await this.delegate.terminateShare(shareCode, terminatedAt);
    this.usageRecorder.recordEstimatedD1Usage({ rowsWritten: 1 });
  }

  async listCleanupCandidates(nowIso: string): Promise<ShareRecord[]> {
    const result = await this.delegate.listCleanupCandidates(nowIso);
    this.usageRecorder.recordEstimatedD1Usage({ rowsRead: Math.max(1, result.length) });
    return result;
  }

  async deleteShare(shareUuid: string): Promise<void> {
    await this.delegate.deleteShare(shareUuid);
    this.usageRecorder.recordEstimatedD1Usage({ rowsWritten: 1 });
  }

  async upsertSession(session: SessionRecord): Promise<void> {
    await this.delegate.upsertSession(session);
    this.usageRecorder.recordEstimatedD1Usage({ rowsWritten: 1 });
  }

  async getSession(sessionKeyHash: string): Promise<SessionRecord | null> {
    const result = await this.delegate.getSession(sessionKeyHash);
    this.usageRecorder.recordEstimatedD1Usage({ rowsRead: 1 });
    return result;
  }

  async deleteExpiredSessions(nowIso: string): Promise<void> {
    await this.delegate.deleteExpiredSessions(nowIso);
    this.usageRecorder.recordEstimatedD1Usage({ rowsWritten: 1 });
  }
}

class InstrumentedObjectStorage implements ObjectStorage {
  constructor(
    private readonly delegate: ObjectStorage,
    private readonly usageRecorder: UsageRecorder,
  ) {}

  async putObject(
    key: string,
    body: BodyInit,
    contentType: string,
    previousSizeHintBytes?: number | null,
  ): Promise<void> {
    const sizeBytes = await buildBodySize(body);
    await this.delegate.putObject(key, body, contentType, previousSizeHintBytes);
    this.usageRecorder.recordR2ClassA(
      sizeBytes - (typeof previousSizeHintBytes === "number" && Number.isFinite(previousSizeHintBytes) ? previousSizeHintBytes : 0),
    );
  }

  async getObject(key: string) {
    const object = await this.delegate.getObject(key);
    this.usageRecorder.recordR2ClassB();
    return object;
  }

  async deleteObject(key: string, sizeHintBytes?: number | null): Promise<void> {
    await this.delegate.deleteObject(key, sizeHintBytes);
    if (typeof sizeHintBytes === "number" && Number.isFinite(sizeHintBytes)) {
      this.usageRecorder.recordR2StorageDelta(-Math.max(0, Math.round(sizeHintBytes)));
    }
  }
}

function buildContext(env: Env, dependencies: AppDependencies = {}): AppContext {
  const usageRecorder = new RequestUsageAccumulator();
  const usageService = dependencies.usageService ?? createUsageService(env.MUSIC_SHARE_DB);
  return {
    settings: dependencies.settings ?? loadSettings(env),
    repository:
      dependencies.repository === undefined
        ? new D1ShareRepository(env.MUSIC_SHARE_DB, usageRecorder)
        : new EstimatedUsageShareRepository(dependencies.repository, usageRecorder),
    storage:
      dependencies.storage === undefined
        ? new R2ObjectStorage(env.MUSIC_SHARE_BUCKET, usageRecorder)
        : new InstrumentedObjectStorage(dependencies.storage, usageRecorder),
    usageService,
    usageRecorder,
  };
}

async function getAuthenticatedSession(
  request: Request,
  context: AppContext,
  adminOnly: boolean,
): Promise<SessionRecord> {
  const authService = new AuthService(context.settings, context.repository);
  const sessionKey = extractSessionKey(request, context.settings.sessionCookieName) ?? "";
  const session = await authService.resolveSession(sessionKey);
  if (session === null) {
    throw new ApiError(401, "Missing or invalid session.");
  }
  if (adminOnly && session.role !== "admin") {
    throw new ApiError(403, "Admin session required.");
  }
  return session;
}

async function getShareOr404(repository: ShareRepository, shareCode: string): Promise<ShareRecord> {
  const share = await repository.getShareByCode(shareCode);
  if (share === null) {
    throw new ApiError(404, "Share not found.");
  }
  return share;
}

function decodePathSegment(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    throw new ApiError(404, "Share not found.");
  }
}

async function parseJsonBody(request: Request): Promise<unknown> {
  try {
    return await request.json();
  } catch {
    throw new ApiError(422, "Request body must be valid JSON.");
  }
}

async function handleLogin(request: Request, context: AppContext): Promise<Response> {
  const payload = await parseJsonBody(request);
  const password =
    payload !== null && typeof payload === "object" && "password" in payload
      ? String((payload as { password: unknown }).password ?? "")
      : "";
  if (!password.trim() || password.trim().length > 256) {
    throw new ApiError(422, "password is required.");
  }

  const authService = new AuthService(context.settings, context.repository);
  const { session, rawToken } = await authService.authenticate(password);
  const response = jsonResponse({
    role: session.role,
    auth_type: session.auth_type,
    session_key: rawToken,
    expires_at: session.expires_at,
  });
  const secure = (context.settings.publicApiBaseUrl ?? request.url).startsWith("https://");
  response.headers.append(
    "set-cookie",
    buildSessionCookie({
      name: context.settings.sessionCookieName,
      value: rawToken,
      maxAge: context.settings.sessionTtlSeconds,
      secure,
    }),
  );
  return response;
}

async function handleUpload(request: Request, context: AppContext): Promise<Response> {
  await getAuthenticatedSession(request, context, false);
  assertUsageAllowsUpload(await context.usageService.getSummary());
  const clientInstallId = requireClientInstallId(request);

  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    throw new ApiError(422, "Request body must be multipart form data.");
  }

  const audioFile = readFormFile(form, "file", true);
  const coverFile = readFormFile(form, "cover", false);
  if (audioFile === null) {
    throw new ApiError(422, "file is required.");
  }
  const title = validateText(readFormText(form, "title", true) ?? "", {
    fieldName: "title",
    maxLength: 256,
    required: true,
  });
  const artist = validateText(readFormText(form, "artist", false) ?? "", {
    fieldName: "artist",
    maxLength: 256,
    required: false,
  });
  const album = validateText(readFormText(form, "album", false) ?? "", {
    fieldName: "album",
    maxLength: 256,
    required: false,
  });
  const audioMime = normalizeMimeType(readFormText(form, "audio_mime", true), {
    allowed: context.settings.allowedAudioMimeTypes,
    fieldName: "audio_mime",
  });
  const durationMs = validateDuration(
    parseRequiredInteger(readFormText(form, "duration_ms", true), "duration_ms"),
    context.settings.maxDurationMs,
  );
  const clientCreatedAt = parseOptionalDateTime(
    readFormText(form, "client_created_at", false),
    "client_created_at",
  );
  const expiresAt = resolveExpiration(
    context.settings,
    parseOptionalInteger(readFormText(form, "expire_after_seconds", false), "expire_after_seconds"),
    readFormText(form, "expire_at", false),
  );
  const share = await createShareRecord({
    repository: context.repository,
    clientInstallId,
    title,
    artist,
    album,
    durationMs,
    audioMime,
    clientCreatedAt,
    expiresAt,
  });

  let persisted: ShareRecord | null = null;
  try {
    persisted = await persistUploads({
      storage: context.storage,
      settings: context.settings,
      share,
      audioFile,
      coverFile,
    });
    await context.repository.insertShare(persisted);
  } catch (error) {
    try {
      await deleteShareAssets(context.storage, persisted ?? share);
    } catch {
      // Best effort cleanup when upload or insert fails.
    }
    throw error;
  }

  return jsonResponse(serializeShareUpload(context.settings, request, persisted));
}

async function handleTrack(request: Request, context: AppContext, shareCode: string): Promise<Response> {
  assertUsageAllowsPublicRead(await context.usageService.getSummary());
  const share = requireShareAvailable(await context.repository.getShareByCode(shareCode));
  const globalBackground = await readGlobalBackgroundConfig(context.storage);
  return jsonResponse(
    serializeSharePublic(context.settings, request, share, {
      includeGlobalBackground: globalBackground !== null,
    }),
  );
}

async function handleStream(context: AppContext, shareCode: string): Promise<Response> {
  assertUsageAllowsPublicRead(await context.usageService.getSummary());
  const share = requireShareAvailable(await context.repository.getShareByCode(shareCode));
  return audioResponse(context.storage, share);
}

async function handleCover(context: AppContext, shareCode: string): Promise<Response> {
  assertUsageAllowsPublicRead(await context.usageService.getSummary());
  const share = await getShareOr404(context.repository, shareCode);
  const status = effectiveStatus(share);
  if (status !== "active") {
    throw new ApiError(410, `Share is ${status}.`);
  }
  return coverResponse(context.storage, share);
}

async function handleBackground(context: AppContext, shareCode: string): Promise<Response> {
  assertUsageAllowsPublicRead(await context.usageService.getSummary());
  const share = await getShareOr404(context.repository, shareCode);
  const status = effectiveStatus(share);
  if (status !== "active") {
    throw new ApiError(410, `Share is ${status}.`);
  }
  return backgroundResponse(context.storage, share);
}

async function handleGlobalBackground(context: AppContext): Promise<Response> {
  assertUsageAllowsPublicRead(await context.usageService.getSummary());
  return globalBackgroundResponse(context.storage);
}

async function handleListClientShares(request: Request, context: AppContext): Promise<Response> {
  await getAuthenticatedSession(request, context, false);
  const clientInstallId = requireClientInstallId(request);
  const items = (await context.repository.listSharesByClient(clientInstallId)).map((share) =>
    serializeShareManagement(context.settings, request, share),
  );
  return jsonResponse({
    items,
    count: items.length,
    generated_at: nowIso(),
  });
}

async function handleGetClientShare(
  request: Request,
  context: AppContext,
  shareCode: string,
): Promise<Response> {
  await getAuthenticatedSession(request, context, false);
  const clientInstallId = requireClientInstallId(request);
  const share = await getShareOr404(context.repository, shareCode);
  if (share.client_install_id !== clientInstallId) {
    throw new ApiError(404, "Share not found.");
  }
  return jsonResponse(serializeShareManagement(context.settings, request, share));
}

async function handleTerminateClientShare(
  request: Request,
  context: AppContext,
  shareCode: string,
): Promise<Response> {
  await getAuthenticatedSession(request, context, false);
  const clientInstallId = requireClientInstallId(request);
  const share = await getShareOr404(context.repository, shareCode);
  if (share.client_install_id !== clientInstallId) {
    throw new ApiError(404, "Share not found.");
  }
  const terminatedAt = nowIso();
  await context.repository.terminateShare(shareCode, terminatedAt);
  const updated: ShareRecord = {
    ...share,
    terminated_at: share.terminated_at ?? terminatedAt,
    status: "terminated",
  };
  await deleteShareAssets(context.storage, updated);
  await context.repository.deleteShare(share.uuid);
  return jsonResponse(serializeShareManagement(context.settings, request, updated));
}

async function handleListAdminTracks(request: Request, context: AppContext): Promise<Response> {
  await getAuthenticatedSession(request, context, true);
  const items = (await context.repository.listAllShares()).map((share) =>
    serializeShareManagement(context.settings, request, share, {
      includeClientInstallId: true,
    }),
  );
  return jsonResponse({
    items,
    count: items.length,
    generated_at: nowIso(),
  });
}

async function handleTerminateAdminTrack(
  request: Request,
  context: AppContext,
  shareCode: string,
): Promise<Response> {
  await getAuthenticatedSession(request, context, true);
  const share = await getShareOr404(context.repository, shareCode);
  const terminatedAt = nowIso();
  await context.repository.terminateShare(shareCode, terminatedAt);
  const updated: ShareRecord = {
    ...share,
    terminated_at: share.terminated_at ?? terminatedAt,
    status: "terminated",
  };
  await deleteShareAssets(context.storage, updated);
  await context.repository.deleteShare(share.uuid);
  return jsonResponse(
    serializeShareManagement(context.settings, request, updated, {
      includeClientInstallId: true,
    }),
  );
}

async function handleUploadAdminTrackBackground(
  request: Request,
  context: AppContext,
  shareCode: string,
): Promise<Response> {
  await getAuthenticatedSession(request, context, true);
  assertUsageAllowsUpload(await context.usageService.getSummary());

  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    throw new ApiError(422, "Request body must be multipart form data.");
  }

  const backgroundFile = readFormFile(form, "background", true);
  if (backgroundFile === null) {
    throw new ApiError(422, "background is required.");
  }

  const share = await getShareOr404(context.repository, shareCode);
  const previousBackgroundPath = share.background_path;

  const persisted = await persistBackgroundUpload({
    storage: context.storage,
    settings: context.settings,
    share,
    backgroundFile,
  });

  try {
    await context.repository.updateShareBackground(
      shareCode,
      persisted.background_mime,
      persisted.background_path,
      persisted.background_bytes,
    );
  } catch (error) {
    await context.storage.deleteObject(persisted.background_path, persisted.background_bytes);
    throw error;
  }

  if (previousBackgroundPath && previousBackgroundPath !== persisted.background_path) {
    try {
      await context.storage.deleteObject(previousBackgroundPath, share.background_bytes);
    } catch {
      // Best effort cleanup for replaced backgrounds.
    }
  }

  const updated = await getShareOr404(context.repository, shareCode);
  return jsonResponse(
    serializeShareManagement(context.settings, request, updated, {
      includeClientInstallId: true,
    }),
  );
}

async function handleGetAdminBackground(request: Request, context: AppContext): Promise<Response> {
  await getAuthenticatedSession(request, context, true);
  return jsonResponse(
    serializeGlobalBackground(context.settings, request, await readGlobalBackgroundConfig(context.storage)),
  );
}

async function handleUploadAdminBackground(request: Request, context: AppContext): Promise<Response> {
  await getAuthenticatedSession(request, context, true);
  assertUsageAllowsUpload(await context.usageService.getSummary());

  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    throw new ApiError(422, "Request body must be multipart form data.");
  }

  const backgroundFile = readFormFile(form, "background", true);
  if (backgroundFile === null) {
    throw new ApiError(422, "background is required.");
  }

  const previous = await readGlobalBackgroundConfig(context.storage);
  const persisted = await persistGlobalBackgroundUpload({
    storage: context.storage,
    settings: context.settings,
    backgroundFile,
    previous,
  });

  if (previous?.path && previous.path !== persisted.path) {
    try {
      await context.storage.deleteObject(previous.path, previous.size);
    } catch {
      // Best effort cleanup for replaced global background.
    }
  }

  return jsonResponse(serializeGlobalBackground(context.settings, request, persisted));
}

async function handleGetAdminUsage(request: Request, context: AppContext): Promise<Response> {
  await getAuthenticatedSession(request, context, true);
  return jsonResponse(serializeUsageSummary(await context.usageService.getSummary()));
}

async function handleUpdateAdminUsage(request: Request, context: AppContext): Promise<Response> {
  await getAuthenticatedSession(request, context, true);
  const payload = parseUsageLimitUpdateInput(await parseJsonBody(request));
  return jsonResponse(serializeUsageSummary(await context.usageService.updateLimits(payload)));
}

async function routeRequest(request: Request, context: AppContext): Promise<Response> {
  const url = new URL(request.url);
  const segments = url.pathname.split("/").filter(Boolean).map(decodePathSegment);

  if (request.method === "OPTIONS") {
    return corsPreflightResponse(request);
  }
  if (request.method === "POST" && url.pathname === "/auth/login") {
    return handleLogin(request, context);
  }
  if (request.method === "POST" && url.pathname === "/upload") {
    return handleUpload(request, context);
  }
  if (request.method === "GET" && url.pathname === "/background") {
    return handleGlobalBackground(context);
  }
  if (request.method === "GET" && segments.length === 2 && segments[0] === "track") {
    return handleTrack(request, context, segments[1]);
  }
  if (request.method === "GET" && segments.length === 2 && segments[0] === "stream") {
    return handleStream(context, segments[1]);
  }
  if (request.method === "GET" && segments.length === 2 && segments[0] === "cover") {
    return handleCover(context, segments[1]);
  }
  if (request.method === "GET" && segments.length === 2 && segments[0] === "background") {
    return handleBackground(context, segments[1]);
  }
  if (request.method === "GET" && url.pathname === "/client/shares") {
    return handleListClientShares(request, context);
  }
  if (request.method === "GET" && segments.length === 3 && segments[0] === "client" && segments[1] === "shares") {
    return handleGetClientShare(request, context, segments[2]);
  }
  if (
    request.method === "POST" &&
    segments.length === 4 &&
    segments[0] === "client" &&
    segments[1] === "shares" &&
    segments[3] === "terminate"
  ) {
    return handleTerminateClientShare(request, context, segments[2]);
  }
  if (request.method === "GET" && url.pathname === "/admin/tracks") {
    return handleListAdminTracks(request, context);
  }
  if (request.method === "GET" && url.pathname === "/admin/background") {
    return handleGetAdminBackground(request, context);
  }
  if (request.method === "GET" && url.pathname === "/admin/usage") {
    return handleGetAdminUsage(request, context);
  }
  if (request.method === "POST" && url.pathname === "/admin/background") {
    return handleUploadAdminBackground(request, context);
  }
  if (request.method === "POST" && url.pathname === "/admin/usage") {
    return handleUpdateAdminUsage(request, context);
  }
  if (
    request.method === "POST" &&
    segments.length === 4 &&
    segments[0] === "admin" &&
    segments[1] === "tracks" &&
    segments[3] === "background"
  ) {
    return handleUploadAdminTrackBackground(request, context, segments[2]);
  }
  if (
    request.method === "POST" &&
    segments.length === 4 &&
    segments[0] === "admin" &&
    segments[1] === "tracks" &&
    segments[3] === "terminate"
  ) {
    return handleTerminateAdminTrack(request, context, segments[2]);
  }
  return errorResponse(404, "Not Found");
}

export async function handleRequest(
  request: Request,
  env: Env,
  dependencies: AppDependencies = {},
): Promise<Response> {
  let context: AppContext | null = null;
  let response: Response;
  try {
    context = buildContext(env, dependencies);
    response = await routeRequest(request, context);
  } catch (error) {
    if (isApiError(error)) {
      response = errorResponse(error.status, error.detail);
    } else {
      console.error("Unhandled request error", error);
      response = errorResponse(500, "Internal Server Error");
    }
  }

  if (context !== null) {
    try {
      await context.usageService.applyRequestUsage(context.usageRecorder.snapshot());
    } catch (error) {
      console.error("Failed to persist usage metrics", error);
    }
  }
  return withCorsHeaders(request, response);
}

export async function runCleanup(env: Env, dependencies: AppDependencies = {}): Promise<void> {
  const context = buildContext(env, dependencies);
  try {
    const now = nowIso();
    const shares = await context.repository.listCleanupCandidates(now);
    for (const share of shares) {
      await deleteShareAssets(context.storage, share);
      await context.repository.deleteShare(share.uuid);
    }
    await context.repository.deleteExpiredSessions(now);
  } finally {
    await context.usageService.applyRequestUsage(context.usageRecorder.snapshot());
  }
}
