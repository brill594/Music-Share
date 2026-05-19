import { ApiError } from "./errors";
import { nowIso } from "./models";
import type { D1Database } from "./types";

const DECIMAL_GB = 1_000_000_000;
const ROLLING_WINDOW_DAYS = 30;
const RETAINED_USAGE_DAYS = 45;

export const CLOUDFLARE_FREE_USAGE_REFERENCE = Object.freeze({
  rollingWindowDays: ROLLING_WINDOW_DAYS,
  d1RowsReadDailyLimit: 5_000_000,
  d1RowsWrittenDailyLimit: 100_000,
  d1StorageBytesLimit: 5 * DECIMAL_GB,
  d1StorageGbLimit: 5,
  r2ClassARolling30dLimit: 1_000_000,
  r2ClassBRolling30dLimit: 10_000_000,
  r2StorageGbMonthLimit: 10,
});

export interface D1QueryMeta {
  rowsRead: number;
  rowsWritten: number;
  sizeAfter: number | null;
}

export interface RequestUsageSnapshot {
  d1RowsRead: number;
  d1RowsWritten: number;
  d1StorageBytes: number | null;
  r2ClassA: number;
  r2ClassB: number;
  r2StorageDeltaBytes: number;
}

export interface UsageRecorder {
  recordD1Meta(meta: D1QueryMeta): void;
  recordEstimatedD1Usage(estimate: {
    rowsRead?: number;
    rowsWritten?: number;
    sizeAfter?: number | null;
  }): void;
  recordR2ClassA(storageDeltaBytes?: number): void;
  recordR2ClassB(): void;
  recordR2StorageDelta(storageDeltaBytes: number): void;
  snapshot(): RequestUsageSnapshot;
}

export interface UsageLimitsConfig {
  enabled: boolean;
  d1RowsReadDailyLimit: number;
  d1RowsWrittenDailyLimit: number;
  d1StorageBytesLimit: number;
  r2ClassARolling30dLimit: number;
  r2ClassBRolling30dLimit: number;
  r2StorageGbMonthLimit: number;
  updatedAt: string;
}

export interface UsageLimitUpdateInput {
  enabled: boolean;
  d1RowsReadDailyLimit: number;
  d1RowsWrittenDailyLimit: number;
  d1StorageGbLimit: number;
  r2ClassARolling30dLimit: number;
  r2ClassBRolling30dLimit: number;
  r2StorageGbMonthLimit: number;
}

export interface UsageSummary {
  enabled: boolean;
  updatedAt: string;
  generatedAt: string;
  d1RowsReadDaily: {
    used: number;
    limit: number;
    exceeded: boolean;
  };
  d1RowsWrittenDaily: {
    used: number;
    limit: number;
    exceeded: boolean;
  };
  d1Storage: {
    usedBytes: number;
    usedGb: number;
    limitGb: number;
    exceeded: boolean;
  };
  r2ClassARolling30d: {
    used: number;
    limit: number;
    exceeded: boolean;
  };
  r2ClassBRolling30d: {
    used: number;
    limit: number;
    exceeded: boolean;
  };
  r2StorageRolling30d: {
    usedGbMonth: number;
    limitGbMonth: number;
    liveBytes: number;
    exceeded: boolean;
  };
  cloudflareReference: typeof CLOUDFLARE_FREE_USAGE_REFERENCE;
}

interface UsageStateRow {
  last_usage_day: string;
  d1_storage_bytes: number;
  r2_storage_live_bytes: number;
}

interface UsageDailyTotalsRow {
  d1_rows_read: number;
  d1_rows_written: number;
  r2_class_a: number;
  r2_class_b: number;
  r2_storage_peak_bytes: number;
}

interface UsageService {
  getSummary(now?: string): Promise<UsageSummary>;
  updateLimits(input: UsageLimitUpdateInput, now?: string): Promise<UsageSummary>;
  applyRequestUsage(snapshot: RequestUsageSnapshot, now?: string): Promise<void>;
}

function toNumber(value: unknown, fallback = 0): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function bytesToGb(value: number): number {
  return value / DECIMAL_GB;
}

function normalizeGb(value: number): number {
  return Math.round(value * 1_000_000) / 1_000_000;
}

function dayKey(iso: string): string {
  return iso.slice(0, 10);
}

function addDays(day: string, delta: number): string {
  const date = new Date(`${day}T00:00:00.000Z`);
  date.setUTCDate(date.getUTCDate() + delta);
  return date.toISOString().slice(0, 10);
}

function sanitizePositiveInteger(value: number, fieldName: string): number {
  if (!Number.isFinite(value) || value <= 0) {
    throw new ApiError(422, `${fieldName} must be a positive number.`);
  }
  return Math.round(value);
}

function sanitizePositiveDecimal(value: number, fieldName: string): number {
  if (!Number.isFinite(value) || value <= 0) {
    throw new ApiError(422, `${fieldName} must be a positive number.`);
  }
  return normalizeGb(value);
}

function buildExceededMetricError(label: string, used: number, limit: number, unit: string): ApiError {
  return new ApiError(429, `${label} limit reached (${used} / ${limit} ${unit}).`);
}

function normalizeMeta(meta: unknown): D1QueryMeta {
  const candidate = meta && typeof meta === "object" ? (meta as Record<string, unknown>) : {};
  const sizeAfter = candidate.size_after;
  return {
    rowsRead: Math.max(0, Math.round(toNumber(candidate.rows_read))),
    rowsWritten: Math.max(0, Math.round(toNumber(candidate.rows_written))),
    sizeAfter:
      typeof sizeAfter === "number" && Number.isFinite(sizeAfter) ? Math.max(0, Math.round(sizeAfter)) : null,
  };
}

export class RequestUsageAccumulator implements UsageRecorder {
  private snapshotValue: RequestUsageSnapshot = {
    d1RowsRead: 0,
    d1RowsWritten: 0,
    d1StorageBytes: null,
    r2ClassA: 0,
    r2ClassB: 0,
    r2StorageDeltaBytes: 0,
  };

  recordD1Meta(meta: D1QueryMeta): void {
    this.snapshotValue.d1RowsRead += meta.rowsRead;
    this.snapshotValue.d1RowsWritten += meta.rowsWritten;
    if (meta.sizeAfter !== null) {
      this.snapshotValue.d1StorageBytes = meta.sizeAfter;
    }
  }

  recordEstimatedD1Usage(estimate: {
    rowsRead?: number;
    rowsWritten?: number;
    sizeAfter?: number | null;
  }): void {
    this.snapshotValue.d1RowsRead += Math.max(0, Math.round(estimate.rowsRead ?? 0));
    this.snapshotValue.d1RowsWritten += Math.max(0, Math.round(estimate.rowsWritten ?? 0));
    if (typeof estimate.sizeAfter === "number" && Number.isFinite(estimate.sizeAfter)) {
      this.snapshotValue.d1StorageBytes = Math.max(0, Math.round(estimate.sizeAfter));
    }
  }

  recordR2ClassA(storageDeltaBytes = 0): void {
    this.snapshotValue.r2ClassA += 1;
    if (storageDeltaBytes !== 0) {
      this.snapshotValue.r2StorageDeltaBytes += Math.round(storageDeltaBytes);
    }
  }

  recordR2ClassB(): void {
    this.snapshotValue.r2ClassB += 1;
  }

  recordR2StorageDelta(storageDeltaBytes: number): void {
    if (!Number.isFinite(storageDeltaBytes) || storageDeltaBytes === 0) {
      return;
    }
    this.snapshotValue.r2StorageDeltaBytes += Math.round(storageDeltaBytes);
  }

  snapshot(): RequestUsageSnapshot {
    return {
      ...this.snapshotValue,
    };
  }
}

class NoopUsageService implements UsageService {
  async getSummary(now: string = nowIso()): Promise<UsageSummary> {
    return {
      enabled: false,
      updatedAt: now,
      generatedAt: now,
      d1RowsReadDaily: { used: 0, limit: CLOUDFLARE_FREE_USAGE_REFERENCE.d1RowsReadDailyLimit, exceeded: false },
      d1RowsWrittenDaily: {
        used: 0,
        limit: CLOUDFLARE_FREE_USAGE_REFERENCE.d1RowsWrittenDailyLimit,
        exceeded: false,
      },
      d1Storage: {
        usedBytes: 0,
        usedGb: 0,
        limitGb: CLOUDFLARE_FREE_USAGE_REFERENCE.d1StorageGbLimit,
        exceeded: false,
      },
      r2ClassARolling30d: {
        used: 0,
        limit: CLOUDFLARE_FREE_USAGE_REFERENCE.r2ClassARolling30dLimit,
        exceeded: false,
      },
      r2ClassBRolling30d: {
        used: 0,
        limit: CLOUDFLARE_FREE_USAGE_REFERENCE.r2ClassBRolling30dLimit,
        exceeded: false,
      },
      r2StorageRolling30d: {
        usedGbMonth: 0,
        limitGbMonth: CLOUDFLARE_FREE_USAGE_REFERENCE.r2StorageGbMonthLimit,
        liveBytes: 0,
        exceeded: false,
      },
      cloudflareReference: CLOUDFLARE_FREE_USAGE_REFERENCE,
    };
  }

  async updateLimits(_input: UsageLimitUpdateInput, now?: string): Promise<UsageSummary> {
    return this.getSummary(now);
  }

  async applyRequestUsage(_snapshot: RequestUsageSnapshot): Promise<void> {}
}

export class InMemoryUsageService implements UsageService {
  private limits: UsageLimitsConfig = {
    enabled: true,
    d1RowsReadDailyLimit: CLOUDFLARE_FREE_USAGE_REFERENCE.d1RowsReadDailyLimit,
    d1RowsWrittenDailyLimit: CLOUDFLARE_FREE_USAGE_REFERENCE.d1RowsWrittenDailyLimit,
    d1StorageBytesLimit: CLOUDFLARE_FREE_USAGE_REFERENCE.d1StorageBytesLimit,
    r2ClassARolling30dLimit: CLOUDFLARE_FREE_USAGE_REFERENCE.r2ClassARolling30dLimit,
    r2ClassBRolling30dLimit: CLOUDFLARE_FREE_USAGE_REFERENCE.r2ClassBRolling30dLimit,
    r2StorageGbMonthLimit: CLOUDFLARE_FREE_USAGE_REFERENCE.r2StorageGbMonthLimit,
    updatedAt: nowIso(),
  };

  private state: UsageStateRow = {
    last_usage_day: dayKey(nowIso()),
    d1_storage_bytes: 0,
    r2_storage_live_bytes: 0,
  };

  private readonly daily = new Map<string, UsageDailyTotalsRow>();

  async getSummary(now: string = nowIso()): Promise<UsageSummary> {
    this.sync(now);
    return this.buildSummary(now);
  }

  async updateLimits(input: UsageLimitUpdateInput, now: string = nowIso()): Promise<UsageSummary> {
    this.limits = {
      enabled: input.enabled,
      d1RowsReadDailyLimit: sanitizePositiveInteger(input.d1RowsReadDailyLimit, "d1_rows_read_daily_limit"),
      d1RowsWrittenDailyLimit: sanitizePositiveInteger(
        input.d1RowsWrittenDailyLimit,
        "d1_rows_written_daily_limit",
      ),
      d1StorageBytesLimit: Math.round(
        sanitizePositiveDecimal(input.d1StorageGbLimit, "d1_storage_gb_limit") * DECIMAL_GB,
      ),
      r2ClassARolling30dLimit: sanitizePositiveInteger(
        input.r2ClassARolling30dLimit,
        "r2_class_a_rolling_30d_limit",
      ),
      r2ClassBRolling30dLimit: sanitizePositiveInteger(
        input.r2ClassBRolling30dLimit,
        "r2_class_b_rolling_30d_limit",
      ),
      r2StorageGbMonthLimit: sanitizePositiveDecimal(
        input.r2StorageGbMonthLimit,
        "r2_storage_gb_month_limit",
      ),
      updatedAt: now,
    };
    return this.getSummary(now);
  }

  async applyRequestUsage(snapshot: RequestUsageSnapshot, now: string = nowIso()): Promise<void> {
    if (
      snapshot.d1RowsRead === 0 &&
      snapshot.d1RowsWritten === 0 &&
      snapshot.d1StorageBytes === null &&
      snapshot.r2ClassA === 0 &&
      snapshot.r2ClassB === 0 &&
      snapshot.r2StorageDeltaBytes === 0
    ) {
      return;
    }
    this.sync(now);
    const today = dayKey(now);
    const current = this.daily.get(today) ?? {
      d1_rows_read: 0,
      d1_rows_written: 0,
      r2_class_a: 0,
      r2_class_b: 0,
      r2_storage_peak_bytes: this.state.r2_storage_live_bytes,
    };
    const nextLiveBytes = Math.max(0, this.state.r2_storage_live_bytes + snapshot.r2StorageDeltaBytes);
    this.daily.set(today, {
      d1_rows_read: current.d1_rows_read + snapshot.d1RowsRead,
      d1_rows_written: current.d1_rows_written + snapshot.d1RowsWritten,
      r2_class_a: current.r2_class_a + snapshot.r2ClassA,
      r2_class_b: current.r2_class_b + snapshot.r2ClassB,
      r2_storage_peak_bytes: Math.max(
        current.r2_storage_peak_bytes,
        this.state.r2_storage_live_bytes,
        nextLiveBytes,
      ),
    });
    this.state = {
      last_usage_day: today,
      d1_storage_bytes: snapshot.d1StorageBytes ?? this.state.d1_storage_bytes,
      r2_storage_live_bytes: nextLiveBytes,
    };
  }

  private sync(now: string): void {
    const today = dayKey(now);
    const lastDay = this.state.last_usage_day;
    if (lastDay > today) {
      this.state = {
        ...this.state,
        last_usage_day: today,
      };
    }
    let cursor = this.state.last_usage_day;
    while (cursor < today) {
      const next = addDays(cursor, 1);
      const existing = this.daily.get(next);
      this.daily.set(next, {
        d1_rows_read: existing?.d1_rows_read ?? 0,
        d1_rows_written: existing?.d1_rows_written ?? 0,
        r2_class_a: existing?.r2_class_a ?? 0,
        r2_class_b: existing?.r2_class_b ?? 0,
        r2_storage_peak_bytes: Math.max(existing?.r2_storage_peak_bytes ?? 0, this.state.r2_storage_live_bytes),
      });
      cursor = next;
    }
    const todayRow = this.daily.get(today);
    this.daily.set(today, {
      d1_rows_read: todayRow?.d1_rows_read ?? 0,
      d1_rows_written: todayRow?.d1_rows_written ?? 0,
      r2_class_a: todayRow?.r2_class_a ?? 0,
      r2_class_b: todayRow?.r2_class_b ?? 0,
      r2_storage_peak_bytes: Math.max(todayRow?.r2_storage_peak_bytes ?? 0, this.state.r2_storage_live_bytes),
    });
    this.state = {
      ...this.state,
      last_usage_day: today,
    };

    const oldest = addDays(today, -(RETAINED_USAGE_DAYS - 1));
    for (const key of this.daily.keys()) {
      if (key < oldest) {
        this.daily.delete(key);
      }
    }
  }

  private buildSummary(now: string): UsageSummary {
    const today = dayKey(now);
    const todayRow = this.daily.get(today) ?? {
      d1_rows_read: 0,
      d1_rows_written: 0,
      r2_class_a: 0,
      r2_class_b: 0,
      r2_storage_peak_bytes: this.state.r2_storage_live_bytes,
    };
    const rollingStart = addDays(today, -(ROLLING_WINDOW_DAYS - 1));
    let r2ClassA = 0;
    let r2ClassB = 0;
    let r2PeakByteDays = 0;
    for (const [day, row] of this.daily.entries()) {
      if (day < rollingStart || day > today) {
        continue;
      }
      r2ClassA += row.r2_class_a;
      r2ClassB += row.r2_class_b;
      r2PeakByteDays += row.r2_storage_peak_bytes;
    }
    const r2StorageUsedGbMonth = normalizeGb(bytesToGb(r2PeakByteDays) / ROLLING_WINDOW_DAYS);
    const d1StorageUsedGb = normalizeGb(bytesToGb(this.state.d1_storage_bytes));
    const d1StorageLimitGb = normalizeGb(bytesToGb(this.limits.d1StorageBytesLimit));

    return {
      enabled: this.limits.enabled,
      updatedAt: this.limits.updatedAt,
      generatedAt: now,
      d1RowsReadDaily: {
        used: todayRow.d1_rows_read,
        limit: this.limits.d1RowsReadDailyLimit,
        exceeded: todayRow.d1_rows_read >= this.limits.d1RowsReadDailyLimit,
      },
      d1RowsWrittenDaily: {
        used: todayRow.d1_rows_written,
        limit: this.limits.d1RowsWrittenDailyLimit,
        exceeded: todayRow.d1_rows_written >= this.limits.d1RowsWrittenDailyLimit,
      },
      d1Storage: {
        usedBytes: this.state.d1_storage_bytes,
        usedGb: d1StorageUsedGb,
        limitGb: d1StorageLimitGb,
        exceeded: this.state.d1_storage_bytes >= this.limits.d1StorageBytesLimit,
      },
      r2ClassARolling30d: {
        used: r2ClassA,
        limit: this.limits.r2ClassARolling30dLimit,
        exceeded: r2ClassA >= this.limits.r2ClassARolling30dLimit,
      },
      r2ClassBRolling30d: {
        used: r2ClassB,
        limit: this.limits.r2ClassBRolling30dLimit,
        exceeded: r2ClassB >= this.limits.r2ClassBRolling30dLimit,
      },
      r2StorageRolling30d: {
        usedGbMonth: r2StorageUsedGbMonth,
        limitGbMonth: this.limits.r2StorageGbMonthLimit,
        liveBytes: this.state.r2_storage_live_bytes,
        exceeded: r2StorageUsedGbMonth >= this.limits.r2StorageGbMonthLimit,
      },
      cloudflareReference: CLOUDFLARE_FREE_USAGE_REFERENCE,
    };
  }
}

export class D1UsageService implements UsageService {
  constructor(private readonly database: D1Database) {}

  async getSummary(now: string = nowIso()): Promise<UsageSummary> {
    const limits = await this.ensureLimits(now);
    const state = await this.syncState(now);
    const today = dayKey(now);
    const todayRow = await this.getDailyTotals(today);
    const rollingStart = addDays(today, -(ROLLING_WINDOW_DAYS - 1));
    const rolling = await this.getRollingTotals(rollingStart, today);
    const r2StorageUsedGbMonth = normalizeGb(bytesToGb(rolling.r2_storage_peak_bytes) / ROLLING_WINDOW_DAYS);
    const d1StorageLimitGb = normalizeGb(bytesToGb(limits.d1StorageBytesLimit));
    return {
      enabled: limits.enabled,
      updatedAt: limits.updatedAt,
      generatedAt: now,
      d1RowsReadDaily: {
        used: todayRow.d1_rows_read,
        limit: limits.d1RowsReadDailyLimit,
        exceeded: todayRow.d1_rows_read >= limits.d1RowsReadDailyLimit,
      },
      d1RowsWrittenDaily: {
        used: todayRow.d1_rows_written,
        limit: limits.d1RowsWrittenDailyLimit,
        exceeded: todayRow.d1_rows_written >= limits.d1RowsWrittenDailyLimit,
      },
      d1Storage: {
        usedBytes: state.d1_storage_bytes,
        usedGb: normalizeGb(bytesToGb(state.d1_storage_bytes)),
        limitGb: d1StorageLimitGb,
        exceeded: state.d1_storage_bytes >= limits.d1StorageBytesLimit,
      },
      r2ClassARolling30d: {
        used: rolling.r2_class_a,
        limit: limits.r2ClassARolling30dLimit,
        exceeded: rolling.r2_class_a >= limits.r2ClassARolling30dLimit,
      },
      r2ClassBRolling30d: {
        used: rolling.r2_class_b,
        limit: limits.r2ClassBRolling30dLimit,
        exceeded: rolling.r2_class_b >= limits.r2ClassBRolling30dLimit,
      },
      r2StorageRolling30d: {
        usedGbMonth: r2StorageUsedGbMonth,
        limitGbMonth: limits.r2StorageGbMonthLimit,
        liveBytes: state.r2_storage_live_bytes,
        exceeded: r2StorageUsedGbMonth >= limits.r2StorageGbMonthLimit,
      },
      cloudflareReference: CLOUDFLARE_FREE_USAGE_REFERENCE,
    };
  }

  async updateLimits(input: UsageLimitUpdateInput, now: string = nowIso()): Promise<UsageSummary> {
    const next: UsageLimitsConfig = {
      enabled: input.enabled,
      d1RowsReadDailyLimit: sanitizePositiveInteger(input.d1RowsReadDailyLimit, "d1_rows_read_daily_limit"),
      d1RowsWrittenDailyLimit: sanitizePositiveInteger(
        input.d1RowsWrittenDailyLimit,
        "d1_rows_written_daily_limit",
      ),
      d1StorageBytesLimit: Math.round(
        sanitizePositiveDecimal(input.d1StorageGbLimit, "d1_storage_gb_limit") * DECIMAL_GB,
      ),
      r2ClassARolling30dLimit: sanitizePositiveInteger(
        input.r2ClassARolling30dLimit,
        "r2_class_a_rolling_30d_limit",
      ),
      r2ClassBRolling30dLimit: sanitizePositiveInteger(
        input.r2ClassBRolling30dLimit,
        "r2_class_b_rolling_30d_limit",
      ),
      r2StorageGbMonthLimit: sanitizePositiveDecimal(
        input.r2StorageGbMonthLimit,
        "r2_storage_gb_month_limit",
      ),
      updatedAt: now,
    };
    await this.run(
      `
        INSERT INTO usage_limits (
          singleton, enabled, d1_rows_read_daily_limit, d1_rows_written_daily_limit,
          d1_storage_bytes_limit, r2_class_a_rolling_30d_limit, r2_class_b_rolling_30d_limit,
          r2_storage_gb_month_limit, updated_at
        ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(singleton) DO UPDATE SET
          enabled = excluded.enabled,
          d1_rows_read_daily_limit = excluded.d1_rows_read_daily_limit,
          d1_rows_written_daily_limit = excluded.d1_rows_written_daily_limit,
          d1_storage_bytes_limit = excluded.d1_storage_bytes_limit,
          r2_class_a_rolling_30d_limit = excluded.r2_class_a_rolling_30d_limit,
          r2_class_b_rolling_30d_limit = excluded.r2_class_b_rolling_30d_limit,
          r2_storage_gb_month_limit = excluded.r2_storage_gb_month_limit,
          updated_at = excluded.updated_at
      `,
      [
        next.enabled ? 1 : 0,
        next.d1RowsReadDailyLimit,
        next.d1RowsWrittenDailyLimit,
        next.d1StorageBytesLimit,
        next.r2ClassARolling30dLimit,
        next.r2ClassBRolling30dLimit,
        next.r2StorageGbMonthLimit,
        now,
      ],
    );
    return this.getSummary(now);
  }

  async applyRequestUsage(snapshot: RequestUsageSnapshot, now: string = nowIso()): Promise<void> {
    if (
      snapshot.d1RowsRead === 0 &&
      snapshot.d1RowsWritten === 0 &&
      snapshot.d1StorageBytes === null &&
      snapshot.r2ClassA === 0 &&
      snapshot.r2ClassB === 0 &&
      snapshot.r2StorageDeltaBytes === 0
    ) {
      return;
    }

    const state = await this.syncState(now);
    const today = dayKey(now);
    const current = await this.getDailyTotals(today);
    const nextLiveBytes = Math.max(0, state.r2_storage_live_bytes + snapshot.r2StorageDeltaBytes);
    const nextPeakBytes = Math.max(current.r2_storage_peak_bytes, state.r2_storage_live_bytes, nextLiveBytes);

    await this.run(
      `
        INSERT INTO usage_daily_metrics (
          usage_day, d1_rows_read, d1_rows_written, r2_class_a, r2_class_b, r2_storage_peak_bytes, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(usage_day) DO UPDATE SET
          d1_rows_read = usage_daily_metrics.d1_rows_read + excluded.d1_rows_read,
          d1_rows_written = usage_daily_metrics.d1_rows_written + excluded.d1_rows_written,
          r2_class_a = usage_daily_metrics.r2_class_a + excluded.r2_class_a,
          r2_class_b = usage_daily_metrics.r2_class_b + excluded.r2_class_b,
          r2_storage_peak_bytes = MAX(usage_daily_metrics.r2_storage_peak_bytes, excluded.r2_storage_peak_bytes),
          updated_at = excluded.updated_at
      `,
      [
        today,
        snapshot.d1RowsRead,
        snapshot.d1RowsWritten,
        snapshot.r2ClassA,
        snapshot.r2ClassB,
        nextPeakBytes,
        now,
      ],
    );

    await this.run(
      `
        UPDATE usage_state
        SET last_usage_day = ?, d1_storage_bytes = ?, r2_storage_live_bytes = ?, updated_at = ?
        WHERE singleton = 1
      `,
      [today, snapshot.d1StorageBytes ?? state.d1_storage_bytes, nextLiveBytes, now],
    );
    await this.deleteExpiredDailyRows(now);
  }

  private async ensureLimits(now: string): Promise<UsageLimitsConfig> {
    await this.run(
      `
        INSERT OR IGNORE INTO usage_limits (
          singleton, enabled, d1_rows_read_daily_limit, d1_rows_written_daily_limit,
          d1_storage_bytes_limit, r2_class_a_rolling_30d_limit, r2_class_b_rolling_30d_limit,
          r2_storage_gb_month_limit, updated_at
        ) VALUES (1, 1, ?, ?, ?, ?, ?, ?, ?)
      `,
      [
        CLOUDFLARE_FREE_USAGE_REFERENCE.d1RowsReadDailyLimit,
        CLOUDFLARE_FREE_USAGE_REFERENCE.d1RowsWrittenDailyLimit,
        CLOUDFLARE_FREE_USAGE_REFERENCE.d1StorageBytesLimit,
        CLOUDFLARE_FREE_USAGE_REFERENCE.r2ClassARolling30dLimit,
        CLOUDFLARE_FREE_USAGE_REFERENCE.r2ClassBRolling30dLimit,
        CLOUDFLARE_FREE_USAGE_REFERENCE.r2StorageGbMonthLimit,
        now,
      ],
    );
    const row = await this.first<Record<string, unknown>>(
      `
        SELECT enabled, d1_rows_read_daily_limit, d1_rows_written_daily_limit, d1_storage_bytes_limit,
               r2_class_a_rolling_30d_limit, r2_class_b_rolling_30d_limit, r2_storage_gb_month_limit, updated_at
        FROM usage_limits
        WHERE singleton = 1
      `,
    );
    return {
      enabled: row?.enabled === 1 || row?.enabled === true || row?.enabled === "1",
      d1RowsReadDailyLimit: Math.max(1, Math.round(toNumber(row?.d1_rows_read_daily_limit))),
      d1RowsWrittenDailyLimit: Math.max(1, Math.round(toNumber(row?.d1_rows_written_daily_limit))),
      d1StorageBytesLimit: Math.max(1, Math.round(toNumber(row?.d1_storage_bytes_limit))),
      r2ClassARolling30dLimit: Math.max(1, Math.round(toNumber(row?.r2_class_a_rolling_30d_limit))),
      r2ClassBRolling30dLimit: Math.max(1, Math.round(toNumber(row?.r2_class_b_rolling_30d_limit))),
      r2StorageGbMonthLimit: sanitizePositiveDecimal(
        toNumber(row?.r2_storage_gb_month_limit, CLOUDFLARE_FREE_USAGE_REFERENCE.r2StorageGbMonthLimit),
        "r2_storage_gb_month_limit",
      ),
      updatedAt: typeof row?.updated_at === "string" ? row.updated_at : now,
    };
  }

  private async ensureState(now: string): Promise<UsageStateRow> {
    const today = dayKey(now);
    await this.run(
      `
        INSERT OR IGNORE INTO usage_state (
          singleton, last_usage_day, d1_storage_bytes, r2_storage_live_bytes, updated_at
        ) VALUES (1, ?, 0, 0, ?)
      `,
      [today, now],
    );
    const row = await this.first<Record<string, unknown>>(
      `
        SELECT last_usage_day, d1_storage_bytes, r2_storage_live_bytes
        FROM usage_state
        WHERE singleton = 1
      `,
    );
    return {
      last_usage_day: typeof row?.last_usage_day === "string" ? row.last_usage_day : today,
      d1_storage_bytes: Math.max(0, Math.round(toNumber(row?.d1_storage_bytes))),
      r2_storage_live_bytes: Math.max(0, Math.round(toNumber(row?.r2_storage_live_bytes))),
    };
  }

  private async syncState(now: string): Promise<UsageStateRow> {
    let state = await this.ensureState(now);
    const today = dayKey(now);
    if (state.last_usage_day > today) {
      await this.run(
        `
          UPDATE usage_state
          SET last_usage_day = ?, updated_at = ?
          WHERE singleton = 1
        `,
        [today, now],
      );
      state = {
        ...state,
        last_usage_day: today,
      };
    }

    let cursor = state.last_usage_day;
    while (cursor < today) {
      const nextDay = addDays(cursor, 1);
      await this.upsertDailyCarryover(nextDay, state.r2_storage_live_bytes, now);
      cursor = nextDay;
    }
    await this.upsertDailyCarryover(today, state.r2_storage_live_bytes, now);
    if (state.last_usage_day !== today) {
      await this.run(
        `
          UPDATE usage_state
          SET last_usage_day = ?, updated_at = ?
          WHERE singleton = 1
        `,
        [today, now],
      );
      state = {
        ...state,
        last_usage_day: today,
      };
    }
    await this.deleteExpiredDailyRows(now);
    return state;
  }

  private async upsertDailyCarryover(day: string, peakBytes: number, now: string): Promise<void> {
    await this.run(
      `
        INSERT INTO usage_daily_metrics (
          usage_day, d1_rows_read, d1_rows_written, r2_class_a, r2_class_b, r2_storage_peak_bytes, updated_at
        ) VALUES (?, 0, 0, 0, 0, ?, ?)
        ON CONFLICT(usage_day) DO UPDATE SET
          r2_storage_peak_bytes = MAX(usage_daily_metrics.r2_storage_peak_bytes, excluded.r2_storage_peak_bytes),
          updated_at = excluded.updated_at
      `,
      [day, peakBytes, now],
    );
  }

  private async getDailyTotals(day: string): Promise<UsageDailyTotalsRow> {
    const row = await this.first<Record<string, unknown>>(
      `
        SELECT d1_rows_read, d1_rows_written, r2_class_a, r2_class_b, r2_storage_peak_bytes
        FROM usage_daily_metrics
        WHERE usage_day = ?
      `,
      [day],
    );
    return {
      d1_rows_read: Math.max(0, Math.round(toNumber(row?.d1_rows_read))),
      d1_rows_written: Math.max(0, Math.round(toNumber(row?.d1_rows_written))),
      r2_class_a: Math.max(0, Math.round(toNumber(row?.r2_class_a))),
      r2_class_b: Math.max(0, Math.round(toNumber(row?.r2_class_b))),
      r2_storage_peak_bytes: Math.max(0, Math.round(toNumber(row?.r2_storage_peak_bytes))),
    };
  }

  private async getRollingTotals(startDay: string, endDay: string): Promise<UsageDailyTotalsRow> {
    const row = await this.first<Record<string, unknown>>(
      `
        SELECT
          COALESCE(SUM(d1_rows_read), 0) AS d1_rows_read,
          COALESCE(SUM(d1_rows_written), 0) AS d1_rows_written,
          COALESCE(SUM(r2_class_a), 0) AS r2_class_a,
          COALESCE(SUM(r2_class_b), 0) AS r2_class_b,
          COALESCE(SUM(r2_storage_peak_bytes), 0) AS r2_storage_peak_bytes
        FROM usage_daily_metrics
        WHERE usage_day >= ? AND usage_day <= ?
      `,
      [startDay, endDay],
    );
    return {
      d1_rows_read: Math.max(0, Math.round(toNumber(row?.d1_rows_read))),
      d1_rows_written: Math.max(0, Math.round(toNumber(row?.d1_rows_written))),
      r2_class_a: Math.max(0, Math.round(toNumber(row?.r2_class_a))),
      r2_class_b: Math.max(0, Math.round(toNumber(row?.r2_class_b))),
      r2_storage_peak_bytes: Math.max(0, Math.round(toNumber(row?.r2_storage_peak_bytes))),
    };
  }

  private async deleteExpiredDailyRows(now: string): Promise<void> {
    const oldest = addDays(dayKey(now), -(RETAINED_USAGE_DAYS - 1));
    await this.run("DELETE FROM usage_daily_metrics WHERE usage_day < ?", [oldest]);
  }

  private async first<T = Record<string, unknown>>(query: string, params: unknown[] = []): Promise<T | null> {
    return this.database.prepare(query).bind(...params).first<T>();
  }

  private async run(query: string, params: unknown[] = []): Promise<void> {
    await this.database.prepare(query).bind(...params).run();
  }
}

export function createUsageService(database: D1Database | null | undefined): UsageService {
  if (!database || typeof database.prepare !== "function") {
    return new NoopUsageService();
  }
  return new D1UsageService(database);
}

export function serializeUsageSummary(summary: UsageSummary): Record<string, unknown> {
  return {
    enabled: summary.enabled,
    updated_at: summary.updatedAt,
    generated_at: summary.generatedAt,
    cloudflare_reference: {
      rolling_window_days: summary.cloudflareReference.rollingWindowDays,
      d1_rows_read_daily_limit: summary.cloudflareReference.d1RowsReadDailyLimit,
      d1_rows_written_daily_limit: summary.cloudflareReference.d1RowsWrittenDailyLimit,
      d1_storage_gb_limit: summary.cloudflareReference.d1StorageGbLimit,
      r2_class_a_rolling_30d_limit: summary.cloudflareReference.r2ClassARolling30dLimit,
      r2_class_b_rolling_30d_limit: summary.cloudflareReference.r2ClassBRolling30dLimit,
      r2_storage_gb_month_limit: summary.cloudflareReference.r2StorageGbMonthLimit,
    },
    d1_rows_read_daily: summary.d1RowsReadDaily,
    d1_rows_written_daily: summary.d1RowsWrittenDaily,
    d1_storage: {
      used_bytes: summary.d1Storage.usedBytes,
      used_gb: summary.d1Storage.usedGb,
      limit_gb: summary.d1Storage.limitGb,
      exceeded: summary.d1Storage.exceeded,
    },
    r2_class_a_rolling_30d: summary.r2ClassARolling30d,
    r2_class_b_rolling_30d: summary.r2ClassBRolling30d,
    r2_storage_rolling_30d: {
      used_gb_month: summary.r2StorageRolling30d.usedGbMonth,
      limit_gb_month: summary.r2StorageRolling30d.limitGbMonth,
      live_bytes: summary.r2StorageRolling30d.liveBytes,
      exceeded: summary.r2StorageRolling30d.exceeded,
    },
  };
}

export function assertUsageAllowsUpload(summary: UsageSummary): void {
  if (!summary.enabled) {
    return;
  }
  if (summary.d1RowsReadDaily.exceeded) {
    throw buildExceededMetricError("D1 rows read (daily)", summary.d1RowsReadDaily.used, summary.d1RowsReadDaily.limit, "rows");
  }
  if (summary.d1RowsWrittenDaily.exceeded) {
    throw buildExceededMetricError(
      "D1 rows written (daily)",
      summary.d1RowsWrittenDaily.used,
      summary.d1RowsWrittenDaily.limit,
      "rows",
    );
  }
  if (summary.d1Storage.exceeded) {
    throw buildExceededMetricError(
      "D1 storage",
      summary.d1Storage.usedGb,
      summary.d1Storage.limitGb,
      "GB",
    );
  }
  if (summary.r2ClassARolling30d.exceeded) {
    throw buildExceededMetricError(
      "R2 Class A (rolling 30d)",
      summary.r2ClassARolling30d.used,
      summary.r2ClassARolling30d.limit,
      "ops",
    );
  }
  if (summary.r2StorageRolling30d.exceeded) {
    throw buildExceededMetricError(
      "R2 storage (rolling 30d)",
      summary.r2StorageRolling30d.usedGbMonth,
      summary.r2StorageRolling30d.limitGbMonth,
      "GB-month",
    );
  }
}

export function assertUsageAllowsPublicRead(summary: UsageSummary): void {
  if (!summary.enabled) {
    return;
  }
  if (summary.d1RowsReadDaily.exceeded) {
    throw buildExceededMetricError("D1 rows read (daily)", summary.d1RowsReadDaily.used, summary.d1RowsReadDaily.limit, "rows");
  }
  if (summary.r2ClassBRolling30d.exceeded) {
    throw buildExceededMetricError(
      "R2 Class B (rolling 30d)",
      summary.r2ClassBRolling30d.used,
      summary.r2ClassBRolling30d.limit,
      "ops",
    );
  }
}

export function parseUsageLimitUpdateInput(payload: unknown): UsageLimitUpdateInput {
  if (payload === null || typeof payload !== "object") {
    throw new ApiError(422, "Request body must be an object.");
  }
  const candidate = payload as Record<string, unknown>;
  return {
    enabled: Boolean(candidate.enabled),
    d1RowsReadDailyLimit: sanitizePositiveInteger(
      toNumber(candidate.d1_rows_read_daily_limit),
      "d1_rows_read_daily_limit",
    ),
    d1RowsWrittenDailyLimit: sanitizePositiveInteger(
      toNumber(candidate.d1_rows_written_daily_limit),
      "d1_rows_written_daily_limit",
    ),
    d1StorageGbLimit: sanitizePositiveDecimal(toNumber(candidate.d1_storage_gb_limit), "d1_storage_gb_limit"),
    r2ClassARolling30dLimit: sanitizePositiveInteger(
      toNumber(candidate.r2_class_a_rolling_30d_limit),
      "r2_class_a_rolling_30d_limit",
    ),
    r2ClassBRolling30dLimit: sanitizePositiveInteger(
      toNumber(candidate.r2_class_b_rolling_30d_limit),
      "r2_class_b_rolling_30d_limit",
    ),
    r2StorageGbMonthLimit: sanitizePositiveDecimal(
      toNumber(candidate.r2_storage_gb_month_limit),
      "r2_storage_gb_month_limit",
    ),
  };
}

export function estimatedUsageRecorder(): UsageRecorder {
  return new RequestUsageAccumulator();
}

export function normalizeD1QueryMeta(meta: unknown): D1QueryMeta {
  return normalizeMeta(meta);
}
