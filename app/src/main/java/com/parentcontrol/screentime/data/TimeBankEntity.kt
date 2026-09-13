package com.parentcontrol.screentime.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 시간 은행(저축) + 대출 + 리워드 + 부모 보호(PIN) 통합 엔티티.
 *
 * - 저축: bankedMillis / maxBankMillis / bankWindow*
 * - 하루 인출 한도: dailyWithdrawLimitMillis (부모가 2~4시간 사이로 설정), withdrawnTodayMillis로 당일 소모량 추적
 * - 대출: loanBalanceMillis(원금+이자), loanDueDate(상환/이자 기준일),
 *   loanRepayMode("PREPAY" 미리쓰기제 / "DEADLINE" 기한제), loanInterestRatePercent
 * - 리워드: rewardPoints (1포인트 = 1분 상당), exchangeTaxPercent(환전세)
 * - 보호: parentPin (설정 null이면 잠금 없음)
 */
@Entity(tableName = "time_bank")
data class TimeBankEntity(
    @PrimaryKey val id: Int = 1,

    // 저축(적립)
    val bankedMillis: Long = 0L,
    val lastSettledDate: String = "",
    val maxBankMillis: Long = 4 * 60 * 60_000L,          // 저축 상한 (기본 4시간)
    val bankWindowStartMinuteOfDay: Int = 20 * 60,
    val bankWindowEndMinuteOfDay: Int = 21 * 60,

    // 하루 인출 한도 (2~4시간 권장, 기본 3시간)
    val dailyWithdrawLimitMillis: Long = 3 * 60 * 60_000L,
    val withdrawnTodayMillis: Long = 0L,
    val withdrawnDate: String = "",

    // 대출
    val loanBalanceMillis: Long = 0L,
    val loanDueDate: String = "",           // PREPAY: 차감 예정일 / DEADLINE: 이자 부과 기준일
    val loanBorrowedTodayMillis: Long = 0L, // 하루 대출 한도(기본 1시간) 체크용
    val loanBorrowedDate: String = "",
    val loanRepayMode: String = "PREPAY",   // "PREPAY" 또는 "DEADLINE"
    val loanInterestRatePercent: Int = 20,

    // 리워드
    val rewardPoints: Long = 0L,
    val exchangeTaxPercent: Int = 10,

    // 부모 설정 보호
    val parentPin: String? = null
)
