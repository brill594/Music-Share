import type { ShareRepository } from "./contracts";
import type { AppSettings } from "./config";
import { ApiError } from "./errors";
import { parseCookies } from "./http";
import { nowIso, type SessionRecord, type SessionRole } from "./models";

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}

function constantTimeEqual(left: string, right: string): boolean {
  let mismatch = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    mismatch |= (left.charCodeAt(index) || 0) ^ (right.charCodeAt(index) || 0);
  }
  return mismatch === 0;
}

function createSessionToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return bytesToHex(bytes);
}

export async function hashSessionKey(sessionKey: string): Promise<string> {
  const encoded = new TextEncoder().encode(sessionKey);
  const digest = await crypto.subtle.digest("SHA-256", encoded);
  return bytesToHex(new Uint8Array(digest));
}

export function extractSessionKey(request: Request, cookieName: string): string | null {
  const direct = request.headers.get("X-Session-Key");
  if (direct) {
    return direct;
  }
  const authorization = request.headers.get("Authorization");
  if (authorization?.startsWith("Bearer ")) {
    return authorization.slice("Bearer ".length).trim();
  }
  return parseCookies(request.headers.get("Cookie")).get(cookieName) ?? null;
}

export class AuthService {
  constructor(
    private readonly settings: AppSettings,
    private readonly repository: ShareRepository,
  ) {}

  async authenticate(password: string): Promise<{ session: SessionRecord; rawToken: string }> {
    const normalized = password.trim();
    let role: SessionRole;
    if (constantTimeEqual(normalized, this.settings.adminPassword)) {
      role = "admin";
    } else if (constantTimeEqual(normalized, this.settings.userPassword)) {
      role = "user";
    } else {
      throw new ApiError(401, "Invalid password.");
    }

    const rawToken = createSessionToken();
    const createdAt = nowIso();
    const session: SessionRecord = {
      session_key_hash: await hashSessionKey(rawToken),
      role,
      auth_type: "api_key",
      created_at: createdAt,
      expires_at: new Date(Date.parse(createdAt) + this.settings.sessionTtlSeconds * 1000).toISOString(),
    };
    await this.repository.upsertSession(session);
    return { session, rawToken };
  }

  async resolveSession(sessionKey: string): Promise<SessionRecord | null> {
    const normalized = sessionKey.trim();
    if (!normalized) {
      return null;
    }
    const session = await this.repository.getSession(await hashSessionKey(normalized));
    if (session === null) {
      return null;
    }
    const now = nowIso();
    if (Date.parse(session.expires_at) <= Date.parse(now)) {
      await this.repository.deleteExpiredSessions(now);
      return null;
    }
    return session;
  }
}
