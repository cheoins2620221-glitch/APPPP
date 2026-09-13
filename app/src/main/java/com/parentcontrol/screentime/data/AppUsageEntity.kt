package com.parentcontrol.screentime.data

import androidx.room.Entity

@Entity(tableName = "app_usage", primaryKeys = ["packageName", "date"])
data class AppUsageEntity(
    val packageName: String,
    val date: String,
    val usedMillis: Long = 0L,
    val bankMillisUsedToday: Long = 0L,
    val graceUsedAt: Long? = null
)
