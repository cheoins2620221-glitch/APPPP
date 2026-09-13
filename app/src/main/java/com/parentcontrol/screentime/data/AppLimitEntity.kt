package com.parentcontrol.screentime.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_limits")
data class AppLimitEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val dailyLimitMinutes: Int,
    val graceEnabled: Boolean = true,
    val gracePeriodSeconds: Int = 120,
    val isMonitored: Boolean = true
)
