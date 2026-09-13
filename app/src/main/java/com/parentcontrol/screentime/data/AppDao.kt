package com.parentcontrol.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // ---- 앱별 한도 설정 ----
    @Query("SELECT * FROM app_limits WHERE isMonitored = 1 ORDER BY appLabel")
    fun observeAllLimits(): Flow<List<AppLimitEntity>>

    @Query("SELECT * FROM app_limits WHERE packageName = :packageName LIMIT 1")
    suspend fun getLimit(packageName: String): AppLimitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLimit(limit: AppLimitEntity)

    @Query("DELETE FROM app_limits WHERE packageName = :packageName")
    suspend fun deleteLimit(packageName: String)

    // ---- 오늘 사용시간 ----
    @Query("SELECT * FROM app_usage WHERE packageName = :packageName AND date = :date LIMIT 1")
    suspend fun getUsage(packageName: String, date: String): AppUsageEntity?

    @Query("SELECT * FROM app_usage WHERE date = :date")
    suspend fun getAllUsageForDate(date: String): List<AppUsageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(usage: AppUsageEntity)

    @Query(
        """
        UPDATE app_usage SET bankMillisUsedToday = bankMillisUsedToday + :deltaMillis
        WHERE packageName = :packageName AND date = :date
        """
    )
    suspend fun addBankUsage(packageName: String, date: String, deltaMillis: Long): Int

    @Query(
        """
        UPDATE app_usage SET graceUsedAt = :timestamp
        WHERE packageName = :packageName AND date = :date
        """
    )
    suspend fun markGraceUsed(packageName: String, date: String, timestamp: Long)

    // ---- 시간 적립(뱅크) ----
    @Query("SELECT * FROM time_bank WHERE id = 1 LIMIT 1")
    suspend fun getBank(): TimeBankEntity?

    @Query("SELECT * FROM time_bank WHERE id = 1 LIMIT 1")
    fun observeBank(): Flow<TimeBankEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBank(bank: TimeBankEntity)
}
