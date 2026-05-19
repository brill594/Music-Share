package com.musicshare.android.ui

import com.musicshare.android.network.AdminUsageDto
import com.musicshare.android.network.CloudflareUsageReferenceDto

data class UsageLimitsDraft(
    val enabled: Boolean,
    val d1RowsReadDailyLimit: String,
    val d1RowsWrittenDailyLimit: String,
    val d1StorageGbLimit: String,
    val r2ClassARolling30dLimit: String,
    val r2ClassBRolling30dLimit: String,
    val r2StorageGbMonthLimit: String,
) {
    companion object {
        fun from(usage: AdminUsageDto): UsageLimitsDraft {
            return UsageLimitsDraft(
                enabled = usage.enabled,
                d1RowsReadDailyLimit = usage.d1RowsReadDaily.limit.toString(),
                d1RowsWrittenDailyLimit = usage.d1RowsWrittenDaily.limit.toString(),
                d1StorageGbLimit = usage.d1Storage.limitGb.stripTrailingZeros(),
                r2ClassARolling30dLimit = usage.r2ClassARolling30d.limit.toString(),
                r2ClassBRolling30dLimit = usage.r2ClassBRolling30d.limit.toString(),
                r2StorageGbMonthLimit = usage.r2StorageRolling30d.limitGbMonth.stripTrailingZeros(),
            )
        }

        fun fromReference(reference: CloudflareUsageReferenceDto, enabled: Boolean = true): UsageLimitsDraft {
            return UsageLimitsDraft(
                enabled = enabled,
                d1RowsReadDailyLimit = reference.d1RowsReadDailyLimit.toString(),
                d1RowsWrittenDailyLimit = reference.d1RowsWrittenDailyLimit.toString(),
                d1StorageGbLimit = reference.d1StorageGbLimit.stripTrailingZeros(),
                r2ClassARolling30dLimit = reference.r2ClassARolling30dLimit.toString(),
                r2ClassBRolling30dLimit = reference.r2ClassBRolling30dLimit.toString(),
                r2StorageGbMonthLimit = reference.r2StorageGbMonthLimit.stripTrailingZeros(),
            )
        }
    }
}

private fun Double.stripTrailingZeros(): String =
    if (this % 1.0 == 0.0) {
        this.toLong().toString()
    } else {
        this.toString().trimEnd('0').trimEnd('.')
    }
