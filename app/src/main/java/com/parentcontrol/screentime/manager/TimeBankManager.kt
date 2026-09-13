package com.parentcontrol.screentime.manager

import com.parentcontrol.screentime.data.AppDao
import com.parentcontrol.screentime.data.TimeBankEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 시간 은행(저축), 인출, 대출, 리워드 환전, 부모 PIN 보호를 모두 담당.
 *
 * 하루 정산(settleIfNeeded) 흐름:
 *   1) 어제 앱별 사용 내역으로 잔여시간(leftover) 계산
 *   2) PREPAY 모드 대출이 있으면 leftover를 대출 상환에 먼저 사용
 *   3) 남은 leftover 중 저축 상한까지는 저축, 초과분은 리워드로 자동 전환
 *   4) DEADLINE 모드 대출이 기한을 넘겼으면 이자 부과 + 기한 연장
 *   5) PREPAY 모드 대출 차감 예정일이 됐으면 저축 잔액에서 남은 대출 잔액 차감
 *   6) 하루 인출 한도 카운터 초기화
 */
class TimeBankManager(private val dao: AppDao) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    companion object {
        const val MAX_LOAN_PER_DAY_MILLIS = 60 * 60_000L // 하루 최대 대출 1시간
    }

    fun todayString(now: Long = System.currentTimeMillis()): String = dateFormat.format(now)

    private fun addDays(dateStr: String, days: Int): String {
        val cal = Calendar.getInstance()
        cal.time = dateFormat.parse(dateStr) ?: return dateStr
        cal.add(Calendar.DAY_OF_MONTH, days)
        return dateFormat.format(cal.time)
    }

    // ---------------- 하루 정산 ----------------

    suspend fun settleIfNeeded(now: Long = System.currentTimeMillis()) {
        val original = dao.getBank() ?: TimeBankEntity()
        val today = todayString(now)
        if (original.lastSettledDate == today) return

        var updated = original

        if (original.lastSettledDate.isNotEmpty()) {
            val usageList = dao.getAllUsageForDate(original.lastSettledDate)
            var leftoverMillis = 0L
            for (usage in usageList) {
                val limit = dao.getLimit(usage.packageName) ?: continue
                val limitMillis = limit.dailyLimitMinutes * 60_000L
                val actuallyUsed = usage.usedMillis - usage.bankMillisUsedToday
                val leftover = limitMillis - actuallyUsed
                if (leftover > 0) leftoverMillis += leftover
            }

            // PREPAY 대출 자동 상환: leftover를 대출 상환에 우선 사용
            if (updated.loanRepayMode == "PREPAY" && updated.loanBalanceMillis > 0) {
                val repay = leftoverMillis.coerceAtMost(updated.loanBalanceMillis)
                updated = updated.copy(loanBalanceMillis = updated.loanBalanceMillis - repay)
                leftoverMillis -= repay
            }

            // 저축 상한까지는 저축, 초과분은 리워드로 자동 전환 (1분 = 1포인트)
            val roomInBank = (updated.maxBankMillis - updated.bankedMillis).coerceAtLeast(0L)
            val toBank = leftoverMillis.coerceAtMost(roomInBank)
            val toReward = leftoverMillis - toBank
            updated = updated.copy(
                bankedMillis = updated.bankedMillis + toBank,
                rewardPoints = updated.rewardPoints + (toReward / 60_000L)
            )
        }

        // DEADLINE 대출: 기한 초과 시 이자 부과 + 기한 연장(복리)
        if (updated.loanRepayMode == "DEADLINE" && updated.loanBalanceMillis > 0 &&
            updated.loanDueDate.isNotEmpty() && today >= updated.loanDueDate
        ) {
            val interest = updated.loanBalanceMillis * updated.loanInterestRatePercent / 100
            updated = updated.copy(
                loanBalanceMillis = updated.loanBalanceMillis + interest,
                loanDueDate = addDays(today, 7)
            )
        }

        // PREPAY 대출: 차감 예정일 도달 시 저축 잔액에서 남은 대출 잔액 차감
        if (updated.loanRepayMode == "PREPAY" && updated.loanBalanceMillis > 0 &&
            updated.loanDueDate.isNotEmpty() && today >= updated.loanDueDate
        ) {
            val deduct = updated.loanBalanceMillis.coerceAtMost(updated.bankedMillis)
            updated = updated.copy(
                bankedMillis = updated.bankedMillis - deduct,
                loanBalanceMillis = updated.loanBalanceMillis - deduct
            )
        }

        // 하루 인출 한도 카운터 초기화
        if (updated.withdrawnDate != today) {
            updated = updated.copy(withdrawnTodayMillis = 0L, withdrawnDate = today)
        }

        dao.upsertBank(updated.copy(lastSettledDate = today))
    }

    // ---------------- 인출(소모) ----------------

    /** 부모가 설정한 적립시간 사용 가능 시간대인지 확인 */
    suspend fun isWithinBankWindow(now: Long = System.currentTimeMillis()): Boolean {
        val bank = dao.getBank() ?: return false
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return minuteOfDay in bank.bankWindowStartMinuteOfDay until bank.bankWindowEndMinuteOfDay
    }

    /** 지금 시점에 실제로 꺼내 쓸 수 있는 최대량 (저축 잔액과 '하루 인출 한도' 중 작은 값) */
    suspend fun remainingWithdrawableMillis(now: Long = System.currentTimeMillis()): Long {
        val bank = dao.getBank() ?: return 0L
        val today = todayString(now)
        val withdrawnToday = if (bank.withdrawnDate == today) bank.withdrawnTodayMillis else 0L
        val dailyCapLeft = (bank.dailyWithdrawLimitMillis - withdrawnToday).coerceAtLeast(0L)
        return bank.bankedMillis.coerceAtMost(dailyCapLeft)
    }

    /** 실제로 저축 잔액을 소모. 하루 인출 한도를 넘지 못하며, 실제로 소모된 양을 반환 */
    suspend fun consumeBank(requestMillis: Long, now: Long = System.currentTimeMillis()): Long {
        val bank = dao.getBank() ?: return 0L
        val today = todayString(now)
        val withdrawnToday = if (bank.withdrawnDate == today) bank.withdrawnTodayMillis else 0L
        val dailyCapLeft = (bank.dailyWithdrawLimitMillis - withdrawnToday).coerceAtLeast(0L)
        val actual = requestMillis.coerceAtMost(dailyCapLeft).coerceAtMost(bank.bankedMillis)
        if (actual <= 0) return 0L
        dao.upsertBank(
            bank.copy(
                bankedMillis = bank.bankedMillis - actual,
                withdrawnTodayMillis = withdrawnToday + actual,
                withdrawnDate = today
            )
        )
        return actual
    }

    // ---------------- 대출 ----------------

    /** 오늘 대출 가능한 남은 한도 (최대 1시간/일) */
    suspend fun remainingLoanQuotaToday(now: Long = System.currentTimeMillis()): Long {
        val bank = dao.getBank() ?: return MAX_LOAN_PER_DAY_MILLIS
        val today = todayString(now)
        val borrowedToday = if (bank.loanBorrowedDate == today) bank.loanBorrowedTodayMillis else 0L
        return (MAX_LOAN_PER_DAY_MILLIS - borrowedToday).coerceAtLeast(0L)
    }

    /** 대출 실행: 성공하면 true, 하루 한도를 넘으면 false */
    suspend fun takeLoan(amountMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
        val bank = dao.getBank() ?: TimeBankEntity()
        val today = todayString(now)
        val borrowedToday = if (bank.loanBorrowedDate == today) bank.loanBorrowedTodayMillis else 0L
        if (borrowedToday + amountMillis > MAX_LOAN_PER_DAY_MILLIS) return false

        val isFirstLoan = bank.loanBalanceMillis <= 0
        val dueDate = when {
            bank.loanRepayMode == "DEADLINE" && isFirstLoan -> addDays(today, 7)
            bank.loanRepayMode == "DEADLINE" -> bank.loanDueDate // 이미 대출 있으면 기존 기한 유지
            else -> addDays(today, 1) // PREPAY: 내일 바로 차감
        }

        dao.upsertBank(
            bank.copy(
                bankedMillis = bank.bankedMillis + amountMillis, // 대출금은 즉시 저축 잔액처럼 사용 가능
                loanBalanceMillis = bank.loanBalanceMillis + amountMillis,
                loanDueDate = dueDate,
                loanBorrowedTodayMillis = borrowedToday + amountMillis,
                loanBorrowedDate = today
            )
        )
        return true
    }

    /** 저축 잔액에서 즉시 수동 상환 (기한제에서 미리 갚고 싶을 때) */
    suspend fun repayLoanNow(amountMillis: Long): Long {
        val bank = dao.getBank() ?: return 0L
        val actual = amountMillis.coerceAtMost(bank.loanBalanceMillis).coerceAtMost(bank.bankedMillis)
        if (actual <= 0) return 0L
        dao.upsertBank(
            bank.copy(
                bankedMillis = bank.bankedMillis - actual,
                loanBalanceMillis = bank.loanBalanceMillis - actual
            )
        )
        return actual
    }

    // ---------------- 리워드 환전 ----------------

    /** 리워드 포인트 -> 저축 시간. 환전세(exchangeTaxPercent)를 뗀 만큼만 시간으로 전환 */
    suspend fun exchangeRewardToTime(points: Long): Boolean {
        val bank = dao.getBank() ?: return false
        if (points <= 0 || points > bank.rewardPoints) return false
        val tax = points * bank.exchangeTaxPercent / 100
        val netMinutes = points - tax
        dao.upsertBank(
            bank.copy(
                rewardPoints = bank.rewardPoints - points,
                bankedMillis = bank.bankedMillis + netMinutes * 60_000L
            )
        )
        return true
    }

    /** 저축 시간(분) -> 리워드 포인트. 환전세를 뗀 만큼만 포인트로 전환 */
    suspend fun exchangeTimeToReward(minutes: Long): Boolean {
        val bank = dao.getBank() ?: return false
        val millis = minutes * 60_000L
        if (millis <= 0 || millis > bank.bankedMillis) return false
        val tax = minutes * bank.exchangeTaxPercent / 100
        val netPoints = minutes - tax
        dao.upsertBank(
            bank.copy(
                bankedMillis = bank.bankedMillis - millis,
                rewardPoints = bank.rewardPoints + netPoints
            )
        )
        return true
    }

    // ---------------- 부모 설정 저장 / PIN ----------------

    suspend fun updateParentSettings(
        maxBankMinutes: Int,
        windowStartMinuteOfDay: Int,
        windowEndMinuteOfDay: Int,
        dailyWithdrawLimitMinutes: Int,
        loanRepayMode: String,
        loanInterestRatePercent: Int,
        exchangeTaxPercent: Int
    ) {
        val bank = dao.getBank() ?: TimeBankEntity(lastSettledDate = todayString())
        dao.upsertBank(
            bank.copy(
                maxBankMillis = maxBankMinutes * 60_000L,
                bankWindowStartMinuteOfDay = windowStartMinuteOfDay,
                bankWindowEndMinuteOfDay = windowEndMinuteOfDay,
                dailyWithdrawLimitMillis = dailyWithdrawLimitMinutes * 60_000L,
                loanRepayMode = loanRepayMode,
                loanInterestRatePercent = loanInterestRatePercent,
                exchangeTaxPercent = exchangeTaxPercent
            )
        )
    }

    /** PIN이 설정되어 있지 않으면 항상 통과, 설정되어 있으면 일치 여부 확인 */
    suspend fun verifyPin(input: String): Boolean {
        val bank = dao.getBank() ?: return true
        return bank.parentPin.isNullOrEmpty() || bank.parentPin == input
    }

    suspend fun hasPin(): Boolean = !dao.getBank()?.parentPin.isNullOrEmpty()

    suspend fun setPin(newPin: String?) {
        val bank = dao.getBank() ?: TimeBankEntity()
        dao.upsertBank(bank.copy(parentPin = newPin?.ifBlank { null }))
    }
}
