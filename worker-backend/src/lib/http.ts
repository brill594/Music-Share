import { ApiError } from "./errors";

const ACCESS_CONTROL_ALLOW_HEADERS = "Content-Type, X-Client-Install-Id, X-Session-Key";
const ACCESS_CONTROL_ALLOW_METHODS = "GET, POST, OPTIONS";
const ACCESS_CONTROL_EXPOSE_HEADERS = "Content-Disposition, Content-Length, Content-Type, ETag";

export function jsonResponse(payload: unknown, init: ResponseInit = {}): Response {
  const headers = new Headers(init.headers);
  if (!headers.has("content-type")) {
    headers.set("content-type", "application/json; charset=utf-8");
  }
  return new Response(JSON.stringify(payload), {
    ...init,
    headers,
  });
}

export function errorResponse(status: number, detail: string): Response {
  return jsonResponse({ detail }, { status });
}

function resolveAllowedOrigin(request: Request): string {
  const origin = request.headers.get("origin");
  return origin && origin.trim() ? origin.trim() : "*";
}

export function withCorsHeaders(request: Request, response: Response): Response {
  const headers = new Headers(response.headers);
  headers.set("access-control-allow-origin", resolveAllowedOrigin(request));
  headers.set("access-control-allow-methods", ACCESS_CONTROL_ALLOW_METHODS);
  headers.set("access-control-allow-headers", ACCESS_CONTROL_ALLOW_HEADERS);
  headers.set("access-control-expose-headers", ACCESS_CONTROL_EXPOSE_HEADERS);
  headers.set("access-control-max-age", "86400");
  headers.append("vary", "Origin");
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

export function corsPreflightResponse(request: Request): Response {
  return withCorsHeaders(
    request,
    new Response(null, {
      status: 204,
    }),
  );
}

export function buildSessionCookie(options: {
  name: string;
  value: string;
  maxAge: number;
  secure: boolean;
}): string {
  const parts = [
    `${options.name}=${encodeURIComponent(options.value)}`,
    "HttpOnly",
    "Path=/",
    "SameSite=Lax",
    `Max-Age=${options.maxAge}`,
  ];
  if (options.secure) {
    parts.push("Secure");
  }
  return parts.join("; ");
}

export function parseCookies(cookieHeader: string | null): Map<string, string> {
  const parsed = new Map<string, string>();
  if (!cookieHeader) {
    return parsed;
  }
  for (const segment of cookieHeader.split(";")) {
    const trimmed = segment.trim();
    if (!trimmed) {
      continue;
    }
    const separatorIndex = trimmed.indexOf("=");
    if (separatorIndex <= 0) {
      continue;
    }
    const key = trimmed.slice(0, separatorIndex).trim();
    const value = trimmed.slice(separatorIndex + 1).trim();
    parsed.set(key, decodeURIComponent(value));
  }
  return parsed;
}

export function readFormText(form: FormData, key: string, required: boolean): string | null {
  const value = form.get(key);
  if (value === null) {
    if (required) {
      throw new ApiError(422, `${key} is required.`);
    }
    return null;
  }
  if (typeof value !== "string") {
    throw new ApiError(422, `${key} must be a string.`);
  }
  return value;
}

export function readFormFile(form: FormData, key: string, required: boolean): File | null {
  const value = form.get(key);
  if (value === null) {
    if (required) {
      throw new ApiError(422, `${key} is required.`);
    }
    return null;
  }
  if (typeof value === "string") {
    throw new ApiError(422, `${key} is required.`);
  }
  return value;
}
