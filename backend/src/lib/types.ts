export interface D1PreparedStatement {
  bind(...values: unknown[]): D1PreparedStatement;
  first<T = Record<string, unknown>>(): Promise<T | null>;
  all<T = Record<string, unknown>>(): Promise<{ results: T[] }>;
  run(): Promise<unknown>;
}

export interface D1Database {
  prepare(query: string): D1PreparedStatement;
}

export interface R2ObjectLike {
  body: BodyInit | null;
  size?: number;
  httpEtag?: string | null;
  httpMetadata?: {
    contentType?: string | null;
  };
}

export interface R2Bucket {
  get(key: string): Promise<R2ObjectLike | null>;
  put(
    key: string,
    value: BodyInit,
    options?: {
      httpMetadata?: {
        contentType?: string;
      };
    },
  ): Promise<void>;
  delete(key: string): Promise<void>;
}

export interface ExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
  passThroughOnException?(): void;
}

export interface ScheduledController {
  readonly cron: string;
  readonly scheduledTime: number;
}

export interface ExportedHandler<Env = unknown> {
  fetch(request: Request, env: Env, ctx: ExecutionContext): Response | Promise<Response>;
  scheduled?(
    controller: ScheduledController,
    env: Env,
    ctx: ExecutionContext,
  ): void | Promise<void>;
}
